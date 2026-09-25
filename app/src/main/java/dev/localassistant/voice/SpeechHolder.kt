package dev.localassistant.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps the speech recognizer warm while the assistant is in use, like the LLM engine. */
class SpeechHolder(private val context: Context, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var loaded: SpeechToText? = null
    private var loadedPack: VoicePack? = null
    private var releaseJob: Job? = null

    /** Warms the recognizer up so the first utterance is not delayed by a cold load. */
    fun preload(pack: VoicePack) {
        releaseJob?.cancel()
        scope.launch { runCatching { acquire(pack) } }
    }

    /**
     * Transcribes while holding the recognizer, so it cannot be swapped or freed halfway and two
     * callers (the panel and another app's dictation) never decode on it at the same time.
     */
    suspend fun transcribe(pack: VoicePack, samples: FloatArray, language: String): String = mutex.withLock {
        releaseJob?.cancel()
        val stt = loadLocked(pack)
        withContext(Dispatchers.Default) { stt.transcribe(samples, language) }
    }

    /** Loads the recognizer; prefer [transcribe], which also keeps it from being freed mid-use. */
    suspend fun acquire(pack: VoicePack): SpeechToText = mutex.withLock {
        releaseJob?.cancel()
        loadLocked(pack)
    }

    private suspend fun loadLocked(pack: VoicePack): SpeechToText {
        loaded?.takeIf { loadedPack == pack }?.let { return it }
        loaded?.close()
        // Cleared first: if the next load fails, the closed recognizer must not be handed out again.
        loaded = null
        loadedPack = null
        val started = System.currentTimeMillis()
        val stt = withContext(Dispatchers.IO) { SpeechToText.load(context, pack) }
        Log.i(TAG, "Loaded ${pack.id} in ${System.currentTimeMillis() - started}ms")
        loaded = stt
        loadedPack = pack
        return stt
    }

    fun scheduleRelease(delayMillis: Long = RELEASE_DELAY_MS) {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(delayMillis)
            mutex.withLock {
                loaded?.close()
                loaded = null
                loadedPack = null
            }
        }
    }

    private companion object {
        const val TAG = "SpeechHolder"
        const val RELEASE_DELAY_MS = 30_000L
    }
}
