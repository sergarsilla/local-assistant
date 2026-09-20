package dev.localassistant.assistant

import android.content.ContextParams
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dev.localassistant.AssistantRuntime
import dev.localassistant.voice.ListenEvent
import dev.localassistant.voice.Microphone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.log10

/**
 * The system-wide recognizer: with this app chosen as the assistant, dictation in other apps runs
 * through the same offline model. Audio is recorded on behalf of the calling app, so the platform
 * shows the caller as the one using the microphone.
 */
class SpeechRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var stopRequested = MutableStateFlow(false)

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val pack = AssistantRuntime.voiceSettings.state.value.speechToText
        if (!pack.isInstalled(this)) {
            Log.i(TAG, "No speech model installed")
            listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return
        }
        val language = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            ?.substringBefore('-')?.substringBefore('_')
            ?: AssistantRuntime.voiceSettings.state.value.language

        job?.cancel()
        stopRequested = MutableStateFlow(false)
        val signal = stopRequested
        val recordingContext = createContext(
            ContextParams.Builder().setNextAttributionSource(listener.callingAttributionSource).build(),
        )
        job = scope.launch {
            try {
                listener.readyForSpeech(Bundle())
                var utterance: FloatArray? = null
                Microphone(this@SpeechRecognitionService).listen(recordingContext, signal).collect { event ->
                    when (event) {
                        is ListenEvent.Level -> listener.rmsChanged(decibels(event.rms))
                        ListenEvent.SpeechStarted -> listener.beginningOfSpeech()
                        is ListenEvent.Utterance -> {
                            listener.endOfSpeech()
                            utterance = event.samples
                        }
                        ListenEvent.NoSpeech -> listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    }
                }
                val samples = utterance ?: return@launch
                val text = AssistantRuntime.speech.acquire(pack).transcribe(samples, language)
                if (text.isBlank()) {
                    listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                } else {
                    listener.results(
                        Bundle().apply {
                            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
                        },
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Recognition failed", e)
                runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            } finally {
                AssistantRuntime.speech.scheduleRelease()
            }
        }
    }

    override fun onStopListening(listener: Callback) {
        stopRequested.value = true
    }

    override fun onCancel(listener: Callback) {
        job?.cancel()
        job = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** SpeechRecognizer clients expect roughly -2 dB (silence) to 10 dB (loud). */
    private fun decibels(rms: Float) = (20f * log10(rms.coerceAtLeast(1e-4f)) / 4f + 8f).coerceIn(-2f, 10f)

    private companion object {
        const val TAG = "SpeechRecognition"
    }
}
