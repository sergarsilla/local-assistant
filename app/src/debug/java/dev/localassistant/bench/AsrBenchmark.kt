package dev.localassistant.bench

import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Transcribes every `sNN.wav` in [clipsDir] and reports one line per clip:
 * `ASR <engine> <clip> <audioMs> <firstOutputMs> <totalMs> <text>`.
 */
class AsrBenchmark(
    private val benchDir: File,
    private val cacheDir: File,
    private val report: (String) -> Unit,
    clipsSubdir: String = "asr-test",
    private val normalize: Boolean = false,
) {
    private val clipsDir = File(benchDir, clipsSubdir)
    private val clips get() = clipsDir.listFiles { f -> f.name.matches(Regex("s\\d+\\.wav")) }.orEmpty().sortedBy { it.name }

    /** Keeps Gemma and a sherpa recognizer loaded together so the cgroup usage can be read from adb. */
    fun runCombined(engine: String) {
        val llm = Engine(
            EngineConfig(
                modelPath = File(benchDir, "gemma-4-E2B-it.litertlm").path,
                backend = Backend.CPU(threadCount = THREADS),
                cacheDir = cacheDir.path,
            ),
        )
        llm.initialize()
        llm.createConversation().use { conversation ->
            runBlocking { conversation.sendMessageAsync("Hola, ¿qué tal?").collect() }
        }
        report("COMBINED llm ready")
        runSherpa(engine, SystemClock.elapsedRealtime())
        llm.createConversation().use { conversation ->
            runBlocking { conversation.sendMessageAsync("Explica en tres frases qué es la luz.").collect() }
        }
        report("COMBINED both used; holding for 20s")
        SystemClock.sleep(20_000)
        llm.close()
    }

    fun run(engine: String) {
        val loadStart = SystemClock.elapsedRealtime()
        when (engine) {
            "gemma" -> runGemma(loadStart)
            else -> runSherpa(engine, loadStart)
        }
    }

    private fun runSherpa(engine: String, loadStart: Long) {
        val dir = File(benchDir, "asr/$engine")
        val model = when (engine) {
            "parakeet" -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$dir/encoder.int8.onnx",
                    decoder = "$dir/decoder.int8.onnx",
                    joiner = "$dir/joiner.int8.onnx",
                ),
                modelType = "nemo_transducer",
                tokens = "$dir/tokens.txt",
            )
            "canary" -> OfflineModelConfig(
                canary = OfflineCanaryModelConfig(
                    encoder = "$dir/encoder.int8.onnx",
                    decoder = "$dir/decoder.int8.onnx",
                    srcLang = "es",
                    tgtLang = "es",
                ),
                tokens = "$dir/tokens.txt",
            )
            "whisper" -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$dir/base-encoder.int8.onnx",
                    decoder = "$dir/base-decoder.int8.onnx",
                    language = "es",
                ),
                tokens = "$dir/base-tokens.txt",
            )
            else -> error("unknown engine $engine")
        }.apply { numThreads = THREADS }

        val recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80), modelConfig = model),
        )
        report("ASR_LOAD $engine ${SystemClock.elapsedRealtime() - loadStart}ms")
        for (clip in clips) {
            val wave = WaveReader.readWave(clip.path)
            val samples = if (normalize) normalized(wave.samples) else wave.samples
            val audioMs = wave.samples.size * 1000L / wave.sampleRate
            val start = SystemClock.elapsedRealtime()
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, wave.sampleRate)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text
            stream.release()
            val elapsed = SystemClock.elapsedRealtime() - start
            report("ASR $engine ${clip.nameWithoutExtension} $audioMs $elapsed $elapsed ${text.oneLine()}")
        }
        recognizer.release()
    }

    private fun runGemma(loadStart: Long) {
        val model = File(benchDir, "gemma-4-E2B-it.litertlm")
        Engine(
            EngineConfig(
                modelPath = model.path,
                backend = Backend.CPU(threadCount = THREADS),
                audioBackend = Backend.CPU(threadCount = THREADS),
                cacheDir = cacheDir.path,
            ),
        ).use { engine ->
            engine.initialize()
            report("ASR_LOAD gemma ${SystemClock.elapsedRealtime() - loadStart}ms")
            for (clip in clips) {
                val bytes = clip.readBytes()
                val audioMs = (bytes.size - WAV_HEADER_BYTES) * 1000L / (16000 * 2)
                engine.createConversation().use { conversation ->
                    val start = SystemClock.elapsedRealtime()
                    var first = 0L
                    val text = StringBuilder()
                    runBlocking {
                        conversation.sendMessageAsync(
                            Message.user(Contents.of(Content.AudioBytes(bytes), Content.Text(TRANSCRIBE_PROMPT))),
                        ).collect {
                            if (first == 0L) first = SystemClock.elapsedRealtime() - start
                            text.append(it.toString())
                        }
                    }
                    val total = SystemClock.elapsedRealtime() - start
                    report("ASR gemma ${clip.nameWithoutExtension} $audioMs $first $total ${text.toString().oneLine()}")
                }
            }
        }
    }

    /** Quiet phone recordings confuse the recognizer, so bring the loudest part up to a normal level. */
    private fun normalized(samples: FloatArray): FloatArray {
        val peak = samples.maxOf { kotlin.math.abs(it) }
        if (peak < 1e-4f || peak > 0.5f) return samples
        val gain = (0.5f / peak).coerceAtMost(20f)
        return FloatArray(samples.size) { samples[it] * gain }
    }

    private fun String.oneLine() = replace('\n', ' ').trim()

    private companion object {
        const val THREADS = 4
        const val WAV_HEADER_BYTES = 44
        const val TRANSCRIBE_PROMPT =
            "Transcribe this Spanish audio verbatim. Output only the transcription, nothing else."
    }
}
