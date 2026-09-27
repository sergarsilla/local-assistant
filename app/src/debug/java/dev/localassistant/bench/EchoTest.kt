package dev.localassistant.bench

import android.content.Context
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import dev.localassistant.AssistantRuntime
import dev.localassistant.voice.AudioFocus
import dev.localassistant.voice.ListenEvent
import dev.localassistant.voice.Microphone
import dev.localassistant.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

/**
 * Checks whether the assistant's own voice, played through the speaker, is picked up as an
 * interruption while the conversation microphone stays open.
 */
class EchoTest(private val context: Context, private val report: (String) -> Unit) {

    fun run() = runBlocking {
        report("ECHO aec_available=${AcousticEchoCanceler.isAvailable()}")
        measure(MediaRecorder.AudioSource.VOICE_RECOGNITION, cancelEcho = true, "recognition+aec")
        measure(MediaRecorder.AudioSource.VOICE_RECOGNITION, cancelEcho = false, "recognition")
    }

    private fun measure(source: Int, cancelEcho: Boolean, label: String) = runBlocking {
        val speaker = Speaker(context, AssistantRuntime.scope, AudioFocus(context)) {
            AssistantRuntime.voiceSettings.state.value.voiceFor(it)
        }
        var selfInterruptions = 0
        var utterances = 0
        var peakLevel = 0f
        val start = SystemClock.elapsedRealtime()
        // The collector must not share a thread with the sleeps below, or events pile up unprocessed.
        val scope = CoroutineScope(Dispatchers.Default)
        val listening = Microphone(context)
            .listen(
                continuous = true,
                strictStart = { speaker.speaking.value },
                cancelEcho = cancelEcho,
                source = source,
            )
            .onEach { event ->
                when (event) {
                    is ListenEvent.Level -> if (speaker.speaking.value) peakLevel = maxOf(peakLevel, event.rms)
                    ListenEvent.SpeechStarted -> if (speaker.speaking.value) {
                        selfInterruptions++
                        report("ECHO self-interruption at ${SystemClock.elapsedRealtime() - start}ms")
                    }
                    is ListenEvent.Utterance -> utterances++
                    ListenEvent.NoSpeech -> Unit
                }
            }
            .launchIn(scope)

        SystemClock.sleep(1_000)
        speaker.begin(AssistantRuntime.voiceSettings.state.value.language)
        speaker.append(TEXT)
        speaker.finish()
        while (speaker.speaking.value && SystemClock.elapsedRealtime() - start < 60_000) SystemClock.sleep(100)
        SystemClock.sleep(1_000)
        listening.cancel()
        scope.cancel()
        report(
            "ECHO $label self_interruptions=$selfInterruptions " +
                "utterances=$utterances peak_level=%.3f".format(peakLevel),
        )
        speaker.close()
    }

    private companion object {
        const val TEXT =
            "Vamos a comprobar si el asistente se escucha a sí mismo mientras habla por el altavoz. " +
                "Esta frase es larga a propósito, para dar tiempo suficiente a que el detector de voz " +
                "reaccione si el eco llega al micrófono. Si todo va bien, no debería registrarse ninguna interrupción."
    }
}
