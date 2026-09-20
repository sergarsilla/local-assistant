package dev.localassistant.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

/** Offline transcription of one finished utterance with a downloaded [VoicePack]. */
class SpeechToText private constructor(private val pack: VoicePack, private val recognizer: OfflineRecognizer) :
    AutoCloseable {

    private var language: String? = null

    fun transcribe(samples: FloatArray, language: String): String {
        if (pack == VoicePack.STT_CANARY && language != this.language) {
            // Canary needs the spoken language up front; Parakeet detects it by itself.
            val config = recognizer.config.copy(
                modelConfig = recognizer.config.modelConfig.copy(
                    canary = recognizer.config.modelConfig.canary.copy(srcLang = language, tgtLang = language),
                ),
            )
            recognizer.setConfig(config)
            this.language = language
        }
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val THREADS = 4

        fun load(context: Context, pack: VoicePack): SpeechToText {
            require(pack.kind == PackKind.SPEECH_TO_TEXT && pack.isInstalled(context)) { "${pack.id} is not installed" }
            val dir = pack.directory(context)
            val model = when (pack) {
                VoicePack.STT_CANARY -> OfflineModelConfig(
                    canary = OfflineCanaryModelConfig(
                        encoder = "$dir/encoder.int8.onnx",
                        decoder = "$dir/decoder.int8.onnx",
                        srcLang = "es",
                        tgtLang = "es",
                        usePnc = true,
                    ),
                    tokens = "$dir/tokens.txt",
                )
                else -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "$dir/encoder.int8.onnx",
                        decoder = "$dir/decoder.int8.onnx",
                        joiner = "$dir/joiner.int8.onnx",
                    ),
                    modelType = "nemo_transducer",
                    tokens = "$dir/tokens.txt",
                )
            }.apply { numThreads = THREADS }
            val recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(featConfig = FeatureConfig(SAMPLE_RATE, 80), modelConfig = model),
            )
            return SpeechToText(pack, recognizer)
        }
    }
}
