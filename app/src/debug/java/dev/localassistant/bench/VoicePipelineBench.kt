package dev.localassistant.bench

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.WaveReader
import dev.localassistant.AssistantRuntime
import dev.localassistant.llm.ChatSession
import dev.localassistant.llm.ReplyListener
import dev.localassistant.voice.Speaker
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Runs everything the panel does except the microphone: a recorded clip goes through the
 * recognizer, the LLM and the voice, reporting where the time goes.
 */
class VoicePipelineBench(private val context: Context, private val report: (String) -> Unit) {

    fun run(clipName: String, language: String) = runBlocking {
        val clip = File(File(context.getExternalFilesDir("bench"), "asr-test"), clipName)
        val wave = WaveReader.readWave(clip.path)
        val prefs = AssistantRuntime.voiceSettings.state.value

        val sttLoadStart = SystemClock.elapsedRealtime()
        val stt = AssistantRuntime.speech.acquire(prefs.speechToText)
        val sttLoaded = SystemClock.elapsedRealtime()

        val text = stt.transcribe(wave.samples, language)
        val transcribed = SystemClock.elapsedRealtime()
        report("VOICE stt_load=${sttLoaded - sttLoadStart}ms transcribe=${transcribed - sttLoaded}ms text=$text")

        val speaker = Speaker(context, AssistantRuntime.scope) { AssistantRuntime.voiceSettings.state.value.voiceFor(it) }
        val chat = ChatSession(AssistantRuntime.engine, AssistantRuntime.scope)
        var firstChunk = 0L
        var firstAudio = 0L
        speaker.begin(language)
        chat.send(
            text,
            object : ReplyListener {
                override fun onChunk(chunk: String) {
                    if (firstChunk == 0L) firstChunk = SystemClock.elapsedRealtime()
                    speaker.append(chunk)
                    if (firstAudio == 0L && speaker.speaking.value) firstAudio = SystemClock.elapsedRealtime()
                }

                override fun onEnd() = speaker.finish()
            },
        )
        while (chat.generating.value || speaker.speaking.value) SystemClock.sleep(50)
        val done = SystemClock.elapsedRealtime()
        report(
            "VOICE reply_first_token=${firstChunk - transcribed}ms " +
                "end_to_end_first_token=${firstChunk - sttLoadStart}ms spoken_until=${done - transcribed}ms",
        )
        report("VOICE reply=${chat.messages.value.lastOrNull()?.text?.replace('\n', ' ')?.take(200)}")
        speaker.close()
    }
}
