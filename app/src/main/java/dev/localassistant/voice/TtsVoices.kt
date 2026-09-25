package dev.localassistant.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Synthesis voices shared by every panel, loaded once and released a while after the last use,
 * like the recognizer. Synthesis runs under the same lock as loading and freeing: a native
 * `generate` cannot be interrupted, so freeing a voice while it runs would crash the process.
 */
class TtsVoices(private val context: Context, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val loaded = mutableMapOf<VoicePack, OfflineTts>()

    @Volatile
    private var releaseJob: Job? = null

    fun preload(pack: VoicePack) {
        releaseJob?.cancel()
        scope.launch { runCatching { mutex.withLock { loadLocked(pack) } } }
    }

    suspend fun sampleRate(pack: VoicePack): Int = mutex.withLock {
        releaseJob?.cancel()
        loadLocked(pack).sampleRate()
    }

    suspend fun synthesize(pack: VoicePack, text: String): FloatArray = mutex.withLock {
        releaseJob?.cancel()
        val tts = loadLocked(pack)
        withContext(Dispatchers.Default) { tts.generate(text, sid = pack.speakerId).samples }
    }

    fun scheduleRelease(delayMillis: Long = RELEASE_DELAY_MS) {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(delayMillis)
            mutex.withLock {
                loaded.values.forEach { it.release() }
                loaded.clear()
            }
        }
    }

    private suspend fun loadLocked(pack: VoicePack): OfflineTts {
        loaded[pack]?.let { return it }
        val started = System.currentTimeMillis()
        val tts = withContext(Dispatchers.IO) { load(pack) }
        Log.i(TAG, "Loaded ${pack.id} in ${System.currentTimeMillis() - started}ms")
        loaded[pack] = tts
        return tts
    }

    private fun load(pack: VoicePack): OfflineTts {
        val dir = pack.directory(context)
        val model = if (pack.isKokoro) {
            OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, "model.int8.onnx").path,
                    voices = File(dir, "voices.bin").path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = File(dir, "espeak-ng-data").path,
                    lexicon = File(dir, "lexicon-us-en.txt").path,
                    lang = pack.language.orEmpty(),
                ),
            )
        } else {
            val onnx = dir.listFiles { f -> f.name.endsWith(".onnx") }.orEmpty().firstOrNull()
                ?: error("${pack.id} has no model file; download it again")
            OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = onnx.path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = File(dir, "espeak-ng-data").path,
                ),
            )
        }
        return OfflineTts(config = OfflineTtsConfig(model = model.apply { numThreads = 2 }))
    }

    private companion object {
        const val TAG = "TtsVoices"
        const val RELEASE_DELAY_MS = 30_000L
    }
}
