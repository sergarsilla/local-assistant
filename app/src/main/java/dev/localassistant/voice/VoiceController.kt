package dev.localassistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.localassistant.llm.ChatSession
import dev.localassistant.llm.ReplyListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface VoiceState {
    data object Idle : VoiceState
    data object NeedsPermission : VoiceState
    data object NeedsModel : VoiceState
    data object Preparing : VoiceState
    data class Listening(val level: Float, val hearingSpeech: Boolean) : VoiceState
    data object Transcribing : VoiceState
}

/**
 * Drives one assistant session's voice loop: listen, transcribe, send to the chat and, when the
 * question was spoken, speak the reply. Speaking again or tapping interrupts the voice.
 */
class VoiceController(
    private val context: Context,
    private val chat: ChatSession,
    private val holder: SpeechHolder,
    private val settings: VoiceSettings,
    private val scope: CoroutineScope,
) {
    private val microphone = Microphone(context)

    private val captures get() = dev.localassistant.AssistantRuntime.captures
    val speaker = Speaker(context, scope) { language -> settings.state.value.voiceFor(language) }
    private var listenJob: Job? = null
    private val captureReset = CaptureReset()


    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _conversation = MutableStateFlow(false)

    /** True while the assistant keeps the microphone open between turns, like a phone call. */
    val conversation: StateFlow<Boolean> = _conversation.asStateFlow()

    val language: String get() = settings.state.value.language

    fun setLanguage(language: String) = settings.update { it.copy(language = language) }

    /** Called when the panel opens: gets the recognizer ready before the user speaks. */
    fun preload() {
        val prefs = settings.state.value
        if (prefs.speechToText.isInstalled(context)) holder.preload(prefs.speechToText)
    }

    fun toggleListening() = if (listenJob?.isActive == true) stopListening() else startListening()

    fun startListening() {
        speaker.stop()
        chat.stop()
        val prefs = settings.state.value
        if (!ready()) return
        listenJob?.cancel()
        listenJob = scope.launch {
            _state.value = VoiceState.Preparing
            try {
                // Load the recognizer while already recording, so the first words are not lost.
                val stt = scope.launch { holder.acquire(prefs.speechToText) }
                var utterance: ListenEvent.Utterance? = null
                microphone.listen().collect { event ->
                    when (event) {
                        is ListenEvent.Level -> _state.value = VoiceState.Listening(
                            level = event.rms,
                            hearingSpeech = (state.value as? VoiceState.Listening)?.hearingSpeech == true,
                        )
                        ListenEvent.SpeechStarted -> _state.value = VoiceState.Listening(0f, hearingSpeech = true)
                        is ListenEvent.Utterance -> utterance = event
                        ListenEvent.NoSpeech -> Unit
                    }
                }
                val captured = utterance ?: return@launch
                if (captured.samples.size / 16 < MIN_UTTERANCE_MS || captured.peakLevel < MIN_UTTERANCE_LEVEL) {
                    Log.i(TAG, "Ignored a short or quiet utterance")
                    return@launch
                }
                _state.value = VoiceState.Transcribing
                stt.join()
                val started = System.currentTimeMillis()
                captures?.save(captured.samples)
                val text = holder.acquire(prefs.speechToText).transcribe(captured.samples, prefs.language)
                Log.i(TAG, "Heard '$text' in ${System.currentTimeMillis() - started}ms")
                if (text.isNotBlank()) chat.send(text, spokenReplyListener())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Listening failed", e)
            } finally {
                if (_state.value !is VoiceState.NeedsPermission && _state.value !is VoiceState.NeedsModel) {
                    _state.value = VoiceState.Idle
                }
            }
        }
    }

    fun toggleConversation() = if (_conversation.value) stopConversation() else startConversation()

    /**
     * Keeps listening between turns. Both capture and playback run on the voice communication
     * path with echo cancellation, which is what lets the microphone stay open while the
     * assistant talks without hearing itself.
     */
    fun startConversation() {
        if (!ready()) return
        stopListening()
        _conversation.value = true
        val prefs = settings.state.value
        listenJob = scope.launch {
            try {
                holder.preload(prefs.speechToText)
                // When the reply ends, whatever the microphone was recording is the assistant's
                // own voice, so that audio is thrown away before listening for the next turn.
                launch {
                    speaker.speaking.collect { speaking -> if (!speaking) captureReset.request() }
                }
                // The call path would cancel the echo, but it also cuts everything above 4 kHz and
                // ramps its gain slowly, which wrecks recognition. Wideband capture wins; the reply
                // is interrupted by tapping instead.
                microphone.listen(continuous = true, reset = captureReset).collect { event ->
                    when (event) {
                        is ListenEvent.Level -> if (!speaker.speaking.value) {
                            _state.value = VoiceState.Listening(event.rms, hearingSpeech = false)
                        }
                        ListenEvent.SpeechStarted ->
                            if (!speaker.speaking.value) _state.value = VoiceState.Listening(0f, hearingSpeech = true)
                        is ListenEvent.Utterance -> handle(event, prefs)
                        ListenEvent.NoSpeech -> Log.i(TAG, "Conversation ended after a long silence")
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Conversation failed", e)
            } finally {
                _conversation.value = false
                if (_state.value !is VoiceState.NeedsPermission && _state.value !is VoiceState.NeedsModel) {
                    _state.value = VoiceState.Idle
                }
            }
        }
    }

    fun stopConversation() {
        _conversation.value = false
        stopListening()
        speaker.stop()
    }

    /** Sends a typed question but answers it out loud, as if it had been spoken. */
    fun ask(text: String) = chat.send(text, spokenReplyListener())

    private suspend fun handle(utterance: ListenEvent.Utterance, prefs: VoicePreferences) {
        if (speaker.speaking.value) {
            // Recorded while the reply was audible, so it is the assistant's own voice.
            Log.i(TAG, "Dropped audio captured while speaking")
            return
        }
        val durationMs = utterance.samples.size / 16
        if (durationMs < MIN_UTTERANCE_MS || utterance.peakLevel < MIN_UTTERANCE_LEVEL) {
            Log.i(TAG, "Ignored ${durationMs}ms at level ${utterance.peakLevel}")
            return
        }
        captures?.save(utterance.samples)
        _state.value = VoiceState.Transcribing
        val text = holder.acquire(prefs.speechToText).transcribe(utterance.samples, prefs.language)
        Log.i(TAG, "Heard '$text' (${durationMs}ms, peak=${utterance.peakLevel})")
        if (text.isBlank() || echoes(text)) {
            // Last safety net: if the words repeat the reply, the cancellation let an echo through.
            if (text.isNotBlank()) Log.i(TAG, "Discarded as echo of the reply")
            _state.value = VoiceState.Listening(0f, hearingSpeech = false)
            return
        }
        speaker.stop()
        chat.stop()
        chat.send(text, spokenReplyListener())
    }

    /** True when the words mostly repeat what the assistant just said. */
    private fun echoes(text: String): Boolean {
        val spoken = speaker.spokenSoFar.lowercase().split(WORDS).filter { it.length > 3 }.toSet()
        val heard = text.lowercase().split(WORDS).filter { it.length > 3 }
        if (heard.isEmpty() || spoken.isEmpty()) return false
        return heard.count { it in spoken }.toFloat() / heard.size >= ECHO_WORD_RATIO
    }

    private fun ready(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = VoiceState.NeedsPermission
            return false
        }
        if (!settings.state.value.speechToText.isInstalled(context)) {
            _state.value = VoiceState.NeedsModel
            return false
        }
        return true
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        _conversation.value = false
        _state.value = VoiceState.Idle
    }

    fun onHide() {
        stopConversation()
        stopListening()
        speaker.stop()
        holder.scheduleRelease()
    }

    fun close() {
        onHide()
        speaker.close()
    }

    private fun spokenReplyListener(): ReplyListener? {
        val prefs = settings.state.value
        if (!prefs.speakReplies || !speaker.canSpeak(prefs.language)) return null
        speaker.begin(prefs.language)
        return object : ReplyListener {
            override fun onChunk(text: String) = speaker.append(text)
            override fun onEnd() = speaker.finish()
        }
    }

    private companion object {
        const val TAG = "VoiceController"
        const val MIN_UTTERANCE_MS = 400
        const val MIN_UTTERANCE_LEVEL = 0.01f
        // Measured on a Pixel 10: speech into the phone peaks around 0.035, the assistant's own echo
        // reaches 0.024 while it talks and drops below 0.008 once playback pauses.
        const val ECHO_WORD_RATIO = 0.5f
        val WORDS = Regex("[^\\p{L}]+")
    }
}
