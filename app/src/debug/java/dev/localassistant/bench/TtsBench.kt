package dev.localassistant.bench

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.localassistant.voice.VoicePack
import java.io.File

/** Speaks one sentence with a candidate voice and reports how much faster than real time it is. */
class TtsBench(private val context: Context, private val report: (String) -> Unit) {

    fun run(pack: VoicePack) {
        val voiceDir = pack.id
        val speakerId = pack.speakerId
        val dir = pack.directory(context)
        val loadStart = SystemClock.elapsedRealtime()
        val tts = OfflineTts(config = configFor(dir))
        val loaded = SystemClock.elapsedRealtime()

        val start = SystemClock.elapsedRealtime()
        val audio = tts.generate(SENTENCE, sid = speakerId, speed = 1.0f)
        val generateMs = SystemClock.elapsedRealtime() - start
        val audioMs = audio.samples.size * 1000L / audio.sampleRate
        report(
            "TTS $voiceDir sid=$speakerId load=${loaded - loadStart}ms generate=${generateMs}ms " +
                "audio=${audioMs}ms speed=%.1fx".format(audioMs.toFloat() / generateMs),
        )
        play(audio.samples, audio.sampleRate)
        tts.release()
    }

    private fun configFor(dir: File): OfflineTtsConfig {
        val kokoroModel = File(dir, "model.int8.onnx")
        val model = if (kokoroModel.exists()) {
            OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = kokoroModel.path,
                    voices = File(dir, "voices.bin").path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = File(dir, "espeak-ng-data").path,
                    lexicon = File(dir, "lexicon-us-en.txt").path,
                    lang = "es",
                ),
            )
        } else {
            OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = dir.listFiles { f -> f.name.endsWith(".onnx") }!!.first().path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = File(dir, "espeak-ng-data").path,
                ),
            )
        }
        return OfflineTtsConfig(model = model.apply { numThreads = 2 })
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(samples.size * 4)
            .build()
        track.play()
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        SystemClock.sleep(300)
        track.stop()
        track.release()
    }

    private companion object {
        const val SENTENCE =
            "Claro que sí. Mañana en Madrid hay veintidós grados y cielo despejado, " +
                "así que puedes dejar el paraguas en casa. ¿Quieres que te ponga una alarma a las siete?"
    }
}
