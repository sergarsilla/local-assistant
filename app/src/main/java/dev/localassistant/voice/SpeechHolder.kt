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
    private var second: SpeechToText? = null
    private var secondPack: VoicePack? = null
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
        val text = withContext(Dispatchers.Default) { stt.transcribe(samples, language) }
        val retry = SecondOpinion.pack(pack, language, text) { it.isInstalled(context) } ?: return@withLock text
        Log.i(TAG, "Transcript came out in another language; retrying with ${retry.id}")
        val second = secondLocked(retry)
        withContext(Dispatchers.Default) { second.transcribe(samples, language) }.ifBlank { text }
    }

    private suspend fun secondLocked(pack: VoicePack): SpeechToText {
        second?.takeIf { secondPack == pack }?.let { return it }
        second?.close()
        second = null
        secondPack = null
        val stt = withContext(Dispatchers.IO) { SpeechToText.load(context, pack) }
        second = stt
        secondPack = pack
        return stt
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
                second?.close()
                second = null
                secondPack = null
            }
        }
    }

    private companion object {
        const val TAG = "SpeechHolder"
        const val RELEASE_DELAY_MS = 30_000L
    }
}

/**
 * Parakeet detects the language itself and is the most accurate model on Spanish, but now and then
 * it hears Spanish as English and writes an English sentence. Canary is told the language, so it
 * cannot drift; it gets a second go at exactly those utterances and nothing else.
 */
internal object SecondOpinion {
    fun pack(used: VoicePack, language: String, text: String, installed: (VoicePack) -> Boolean): VoicePack? {
        if (used != VoicePack.STT_PARAKEET || text.isBlank()) return null
        val heard = LanguageGuess.of(text) ?: return null
        if (heard == language) return null
        return VoicePack.STT_CANARY.takeIf(installed)
    }
}
