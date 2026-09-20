package dev.localassistant.bench

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import dev.localassistant.AssistantRuntime
import dev.localassistant.voice.ListenEvent
import dev.localassistant.voice.Microphone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlin.math.max

/**
 * Records the same spoken phrase through several capture paths and reports how loud the signal
 * arrives and what the recognizer makes of it. Beeps once before each take.
 */
class MicCalibration(private val context: Context, private val report: (String) -> Unit) {

    private data class Setup(val label: String, val source: Int, val cancelEcho: Boolean, val communicationMode: Boolean)

    fun run(seconds: Int) = runBlocking {
        report("MICCAL aec_available=${AcousticEchoCanceler.isAvailable()}")
        val setups = listOf(
            Setup("recognition", MediaRecorder.AudioSource.VOICE_RECOGNITION, cancelEcho = false, communicationMode = false),
            Setup("recognition+aec", MediaRecorder.AudioSource.VOICE_RECOGNITION, cancelEcho = true, communicationMode = false),
            Setup("communication", MediaRecorder.AudioSource.VOICE_COMMUNICATION, cancelEcho = false, communicationMode = false),
            Setup("communication+aec+mode", MediaRecorder.AudioSource.VOICE_COMMUNICATION, cancelEcho = true, communicationMode = true),
        )
        val audio = context.getSystemService(AudioManager::class.java)
        val tones = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        val stt = AssistantRuntime.speech.acquire(AssistantRuntime.voiceSettings.state.value.speechToText)
        for (setup in setups) {
            tones.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            SystemClock.sleep(500)
            val previousMode = audio.mode
            if (setup.communicationMode) audio.mode = AudioManager.MODE_IN_COMMUNICATION
            var peak = 0f
            var sum = 0.0
            var levels = 0
            var speechWindows = 0
            var captured: FloatArray? = null
            val scope = CoroutineScope(Dispatchers.Default)
            val job = Microphone(context)
                .listen(continuous = true, cancelEcho = setup.cancelEcho, source = setup.source)
                .onEach { event ->
                    when (event) {
                        is ListenEvent.Level -> {
                            peak = max(peak, event.rms)
                            sum += event.rms
                            levels++
                        }
                        ListenEvent.SpeechStarted -> speechWindows++
                        is ListenEvent.Utterance -> if (captured == null) captured = event.samples
                        ListenEvent.NoSpeech -> Unit
                    }
                }
                .launchIn(scope)
            SystemClock.sleep(seconds * 1000L)
            job.cancel()
            scope.cancel()
            if (setup.communicationMode) audio.mode = previousMode
            val text = captured?.let { stt.transcribe(it, AssistantRuntime.voiceSettings.state.value.language) } ?: ""
            report(
                "MICCAL ${setup.label} peak=%.3f mean=%.3f starts=%d captured=%dms text='%s'".format(
                    peak, if (levels > 0) sum / levels else 0.0, speechWindows, (captured?.size ?: 0) / 16, text,
                ),
            )
            tones.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            SystemClock.sleep(800)
        }
        tones.release()
        report("MICCAL done")
    }
}
