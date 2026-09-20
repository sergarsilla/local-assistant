package dev.localassistant.bench

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import dev.localassistant.AssistantRuntime
import dev.localassistant.llm.ChatMessage
import dev.localassistant.llm.GenerationEvent
import dev.localassistant.llm.LlamaCppEngine
import dev.localassistant.llm.Role
import dev.localassistant.models.ModelDownloadService
import dev.localassistant.models.RemoteModelFile
import dev.localassistant.voice.VoicePack
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * Usage: am start -n dev.localassistant/.bench.BenchmarkActivity
 *   --es model <file in files/models or external files/models> --es backend cpu|gpu|npu|tensor [--ei threads N] [--ez chat true]
 *   --es install <file in external files/bench>  copies a pushed model into the app's model store
 *   --es activate <installed model name>          makes it the assistant's active model
 *   --es download <repo>:<path> [--el size N]     downloads through the real download service
 *   --es asr parakeet|canary|whisper|gemma [--es clips dir] [--ez normalize true]
 *   --es mkdirs a,b/c                             creates app-owned directories under bench/
 *   --es pack stt-canary|tts-es|…                 downloads a voice pack through the real service
 *   --es stt stt-parakeet|stt-canary              picks the recognizer
 *   --es voice sNN.wav [--es lang es|en]          runs recognizer -> model -> spoken reply
 *   --es tts <voice pack id>                      speaks one sentence with an installed voice
 *   --ez echotest true                            checks whether the speaker echoes into the mic
 *   --ez miccal true [--ei seconds N]             compares capture paths on the same spoken phrase
 *   --ez convtest true                            runs one spoken reply through the conversation loop
 *   --es toolask "question"                       asks with tools enabled and reports tool usage
 *   --es tool <name> [--es args '{"a":1}']        runs one tool directly, bypassing the model
 *   --ez historytest true                         checks that deleting a conversation wipes its text
 * Results are written to logcat under the "LocalAssistantBench" tag.
 */
class BenchmarkActivity : Activity() {

    @OptIn(ExperimentalApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("install")?.let { return install(it) }
        intent.getStringExtra("download")?.let { spec ->
            val (repo, path) = spec.substringBeforeLast(':') to spec.substringAfterLast(':')
            ModelDownloadService.start(this, RemoteModelFile(repo, path, intent.getLongExtra("size", 0)))
            return finishWith("download started $spec")
        }
        intent.getStringExtra("mkdirs")?.let { dirs ->
            // FUSE hides adb-created directories in Android/data from the app, so the app creates them.
            dirs.split(',').forEach { File(getExternalFilesDir("bench"), it.trim()).mkdirs() }
            return finishWith("created $dirs")
        }
        if (intent.getBooleanExtra("historytest", false)) {
            thread(name = "history") {
                runCatching { HistoryTest(this, ::report).run() }.onFailure { report("FAILED historytest: $it") }
                report("DONE")
                runOnUiThread { finish() }
            }
            return
        }
        intent.getStringExtra("tool")?.let { toolName ->
            val arguments = intent.getStringExtra("args") ?: "{}"
            val tool = AssistantRuntime.tools.available().firstOrNull { it.name == toolName }
                ?: return finishWith("unknown tool $toolName")
            thread(name = "tool") {
                runCatching { report("TOOLRUN $toolName -> ${tool.run(org.json.JSONObject(arguments))}") }
                    .onFailure { report("FAILED tool $toolName: $it") }
                report("DONE")
                runOnUiThread { finish() }
            }
            return
        }
        intent.getStringExtra("toolask")?.let { question ->
            thread(name = "toolask") {
                runCatching { ToolTest(this, ::report).run(question) }.onFailure { report("FAILED toolask: $it") }
                report("DONE")
                runOnUiThread { finish() }
            }
            return
        }
        if (intent.getBooleanExtra("convtest", false)) {
            thread(name = "conv") {
                runCatching { ConversationTest(this, ::report).run() }.onFailure { report("FAILED convtest: $it") }
                report("DONE")
                runOnUiThread { finish() }
            }
            return
        }
        if (intent.getBooleanExtra("miccal", false)) {
            thread(name = "miccal") {
                runCatching { MicCalibration(this, ::report).run(intent.getIntExtra("seconds", 6)) }
                    .onFailure { report("FAILED miccal: $it") }
                report("DONE")
                runOnUiThread { finish() }
            }
            return
        }
        if (intent.getBooleanExtra("echotest", false)) {
            thread(name = "echo") {
                runCatching { EchoTest(this, ::report).run() }.onFailure { report("FAILED echotest: $it") }
                report("DONE")
                runOnUiThread { finish() }
            }
            return
        }
        intent.getStringExtra("tts")?.let { packId ->
            thread(name = "tts") {
                runCatching { TtsBench(this, ::report).run(VoicePack.byId(packId)) }
                    .onFailure { report("FAILED tts $packId: $it") }
                report("DONE")
                runOnUiThread { finish() }
            }
            return
        }
        intent.getStringExtra("voice")?.let { clip ->
            thread(name = "voice") {
                runCatching { VoicePipelineBench(this, ::report).run(clip, intent.getStringExtra("lang") ?: "es") }
                    .onFailure { report("FAILED voice $clip: $it") }
                report("MEM ${memoryStats()}")
                report("DONE")
                runOnUiThread { finish() }
            }
            return
        }
        intent.getStringExtra("stt")?.let { id ->
            AssistantRuntime.voiceSettings.update { it.copy(speechToText = VoicePack.byId(id)) }
            return finishWith("speech recognizer set to $id")
        }
        intent.getStringExtra("pack")?.let { id ->
            ModelDownloadService.startPack(this, VoicePack.byId(id))
            return finishWith("pack download started $id")
        }
        intent.getStringExtra("asr")?.let { engine ->
            thread(name = "asr") {
                val bench = AsrBenchmark(
                    benchDir = getExternalFilesDir("bench")!!,
                    cacheDir = cacheDir,
                    report = ::report,
                    clipsSubdir = intent.getStringExtra("clips") ?: "asr-test",
                    normalize = intent.getBooleanExtra("normalize", false),
                )
                runCatching {
                    if (intent.getBooleanExtra("combined", false)) bench.runCombined(engine) else bench.run(engine)
                }
                    .onFailure { report("FAILED asr $engine: $it") }
                report("MEM ${memoryStats()}")
                report("DONE")
                runOnUiThread { finish() }
            }
            return
        }
        intent.getStringExtra("activate")?.let {
            AssistantRuntime.models.setActive(it)
            AssistantRuntime.engine.releaseNow()
            return finishWith("activated $it")
        }
        val modelName = intent.getStringExtra("model") ?: return finishWith("missing model extra")
        val backendName = intent.getStringExtra("backend") ?: "cpu"
        val threads = intent.getIntExtra("threads", 0)
        val chat = intent.getBooleanExtra("chat", false)
        // Created by the app itself: FUSE denies access to dirs in Android/data created by adb.
        val externalDir = getExternalFilesDir("bench")
        val model = listOfNotNull(File(filesDir, "models"), externalDir)
            .map { File(it, modelName) }
            .firstOrNull { it.exists() }
            ?: return finishWith("model not found: $modelName in ${externalDir?.path} -> ${externalDir?.list()?.toList()}")
        if (!model.canRead()) return finishWith("model not readable: ${model.path}")

        val backend = when (backendName) {
            "gpu" -> Backend.GPU()
            "npu" -> Backend.NPU(applicationInfo.nativeLibraryDir)
            "tensor" -> Backend.GOOGLE_TENSOR()
            else -> Backend.CPU(threadCount = threads.takeIf { it > 0 })
        }
        val cache = File(cacheDir, "litertlm").apply { mkdirs() }.path

        thread(name = "bench") {
            try {
                when {
                    modelName.endsWith(".gguf") -> runLlamaChat(model, threads.takeIf { it > 0 } ?: 4)
                    chat -> runChat(model, backend, cache)
                    else -> runSynthetic(model, backend, cache)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "FAILED", t)
                report("FAILED backend=$backendName model=$modelName: $t")
            }
            report("MEM ${memoryStats()}")
            report("DONE")
            runOnUiThread { finish() }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun runSynthetic(model: File, backend: Backend, cache: String) {
        val info = benchmark(
            modelPath = model.path,
            backend = backend,
            prefillTokens = PREFILL_TOKENS,
            decodeTokens = DECODE_TOKENS,
            cacheDir = cache,
        )
        report(
            "RESULT backend=${backend.name} init=%.2fs ttft=%.2fs prefill=%.1f tok/s (%d) decode=%.1f tok/s (%d)".format(
                info.initTimeInSecond, info.timeToFirstTokenInSecond,
                info.lastPrefillTokensPerSecond, info.lastPrefillTokenCount,
                info.lastDecodeTokensPerSecond, info.lastDecodeTokenCount,
            ),
        )
    }

    private fun runChat(model: File, backend: Backend, cache: String) {
        val start = SystemClock.elapsedRealtime()
        Engine(EngineConfig(modelPath = model.path, backend = backend, cacheDir = cache)).use { engine ->
            engine.initialize()
            val loaded = SystemClock.elapsedRealtime()
            engine.createConversation().use { conversation ->
                val sent = SystemClock.elapsedRealtime()
                var firstChunkAt = 0L
                var chunks = 0
                val text = StringBuilder()
                runBlocking {
                    conversation.sendMessageAsync(CHAT_PROMPT).collect { message ->
                        if (firstChunkAt == 0L) firstChunkAt = SystemClock.elapsedRealtime()
                        chunks++
                        text.append(message.toString())
                    }
                }
                val end = SystemClock.elapsedRealtime()
                report(
                    "CHAT backend=${backend.name} load=${loaded - start}ms ttft=${firstChunkAt - sent}ms " +
                        "total=${end - sent}ms chunks=$chunks chunksPerSec=%.1f".format(
                            (chunks - 1) * 1000.0 / (end - firstChunkAt).coerceAtLeast(1),
                        ),
                )
                report("CHAT_TEXT ${text.toString().replace('\n', ' ')}")
            }
        }
    }

    private fun install(name: String) {
        thread(name = "install") {
            val source = File(getExternalFilesDir("bench"), name)
            val target = File(AssistantRuntime.models.directory, name)
            runCatching { source.copyTo(target, overwrite = true) }
                .onSuccess {
                    AssistantRuntime.models.recordSource(name, "adb")
                    report("INSTALLED $name ${target.length()} bytes")
                }
                .onFailure { report("ERROR install $name: $it") }
            report("DONE")
            runOnUiThread { finish() }
        }
    }

    /** Same engine and process limits as the assistant panel, plus page-fault and memcg counters. */
    private fun runLlamaChat(model: File, threads: Int) {
        val loadStart = SystemClock.elapsedRealtime()
        LlamaCppEngine.load(model, 4096, threads).use { engine ->
            report("LOAD ${SystemClock.elapsedRealtime() - loadStart}ms ${faults()}")
            val messages = listOf(ChatMessage(Role.USER, LONG_PROMPT))
            val before = faults()
            runBlocking {
                engine.generate(messages, maxTokens = DECODE_TOKENS).collect { event ->
                    if (event is GenerationEvent.Finished) event.stats?.let {
                        report(
                            "LLAMA prompt=${it.promptTokens} prefill=${it.prefillMillis}ms " +
                                "decode=${it.generatedTokens} tok in ${it.generateMillis}ms (%.1f tok/s)".format(it.tokensPerSecond),
                        )
                    }
                }
            }
            report("FAULTS before=[$before] after=[${faults()}]")
        }
    }

    private fun faults(): String {
        val majflt = File("/proc/self/stat").readText().substringAfter(") ").split(" ")[9]
        val high = File("/proc/self/cgroup").readLines().firstOrNull { it.startsWith("0::") }
            ?.substringAfter("0::")
            ?.let { File("/sys/fs/cgroup$it/memory.events").takeIf(File::canRead)?.readLines() }
            ?.firstOrNull { it.startsWith("high") } ?: "high n/a"
        return "majflt=$majflt $high"
    }

    private fun memoryStats(): String =
        File("/proc/self/status").readLines()
            .filter { it.startsWith("VmHWM") || it.startsWith("VmRSS") }
            .joinToString(" ") { it.replace(Regex("\\s+"), " ") }

    /** Logcat rotates too fast on this device to hold results, so they also go to a file. */
    private fun report(line: String) {
        Log.i(TAG, line)
        getExternalFilesDir("bench")?.let { File(it, RESULTS_FILE).appendText(line + "\n") }
    }

    private fun finishWith(error: String) {
        report("ERROR $error")
        report("DONE")
        finish()
    }

    companion object {
        private const val TAG = "LocalAssistantBench"
        private const val RESULTS_FILE = "results.txt"
        private const val PREFILL_TOKENS = 256
        private const val DECODE_TOKENS = 128
        private const val LONG_PROMPT =
            "Explica con detalle, en unos seis puntos, qué es un agujero negro y cómo se forma."
        private const val CHAT_PROMPT =
            "Explícame en tres frases, de forma sencilla, por qué el cielo es azul."
    }
}
