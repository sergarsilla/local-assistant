package dev.localassistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DictationState {
    data object Idle : DictationState
    data class Listening(val level: Float, val hearingSpeech: Boolean) : DictationState
    data object Transcribing : DictationState
    data object NeedsPermission : DictationState
    data object NeedsModel : DictationState
    data object NothingHeard : DictationState
}

/**
 * One utterance to text, for dictation into other apps: listen until the speaker pauses (or
 * [finishSpeaking]), transcribe offline, hand back the words. Used by the voice keyboard and by
 * the dialog behind other apps' microphone buttons.
 */
class Dictation(
    private val context: Context,
    private val scope: CoroutineScope,
    private val speech: SpeechHolder,
    private val settings: VoiceSettings,
) {
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private var job: Job? = null
    private var stop = MutableStateFlow(false)

    val isActive: Boolean get() = job?.isActive == true

    /** Loads the recognizer ahead of time, e.g. when the keyboard appears. */
    fun prepare() {
        settings.state.value.activeSpeechToText?.let(speech::preload)
    }

    /**
     * Starts listening. [language] is a BCP-47 tag or code from the caller ("es-ES"); null uses
     * the assistant's language. [onText] runs on the scope's dispatcher with non-blank text.
     */
    fun start(language: String?, onText: (String) -> Unit) {
        cancel()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = DictationState.NeedsPermission
            return
        }
        val prefs = settings.state.value
        val pack = prefs.activeSpeechToText ?: run {
            _state.value = DictationState.NeedsModel
            return
        }
        val code = language?.substringBefore('-')?.substringBefore('_')?.lowercase()?.ifBlank { null } ?: prefs.language
        val signal = MutableStateFlow(false).also { stop = it }
        speech.preload(pack)
        job = scope.launch {
            _state.value = DictationState.Listening(0f, hearingSpeech = false)
            try {
                var samples: FloatArray? = null
                Microphone(context).listen(stopRequested = signal).collect { event ->
                    when (event) {
                        is ListenEvent.Level -> _state.value = DictationState.Listening(
                            event.rms,
                            (_state.value as? DictationState.Listening)?.hearingSpeech == true,
                        )
                        ListenEvent.SpeechStarted -> _state.value = DictationState.Listening(0f, hearingSpeech = true)
                        is ListenEvent.Utterance -> samples = event.samples
                        ListenEvent.NoSpeech -> Unit
                    }
                }
                val captured = samples ?: run {
                    _state.value = DictationState.NothingHeard
                    return@launch
                }
                _state.value = DictationState.Transcribing
                val text = speech.transcribe(pack, captured, code).trim()
                _state.value = if (text.isEmpty()) DictationState.NothingHeard else DictationState.Idle
                if (text.isNotEmpty()) onText(text)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Dictation failed", e)
                _state.value = DictationState.NothingHeard
            } finally {
                speech.scheduleRelease()
            }
        }
    }

    /** Ends the utterance now instead of waiting for the trailing silence. */
    fun finishSpeaking() {
        stop.value = true
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = DictationState.Idle
    }

    private companion object {
        const val TAG = "Dictation"
    }
}
