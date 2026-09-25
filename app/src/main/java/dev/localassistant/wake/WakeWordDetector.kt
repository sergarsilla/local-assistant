package dev.localassistant.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Listens for one phrase with a 3.3M-parameter streaming keyword spotter bundled in the APK:
 * small enough to run continuously on one little core.
 */
class WakeWordDetector(private val context: Context) {

    /** Records until [phrase] is heard (returns true) or the coroutine is cancelled. */
    @SuppressLint("MissingPermission") // The service checks RECORD_AUDIO before starting.
    suspend fun awaitPhrase(phrase: WakePhrase): Boolean = withContext(Dispatchers.IO) {
        val spotter = KeywordSpotter(context.assets, config())
        val stream = spotter.createStream(phrase.tokens)
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            CHUNK * 2 * 4,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        try {
            record.startRecording()
            val pcm = ShortArray(CHUNK)
            val samples = FloatArray(CHUNK)
            while (currentCoroutineContext().isActive) {
                val read = record.read(pcm, 0, CHUNK)
                if (read <= 0) continue
                for (i in 0 until read) samples[i] = pcm[i] / 32768f
                stream.acceptWaveform(if (read == CHUNK) samples else samples.copyOf(read), SAMPLE_RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val keyword = spotter.getResult(stream).keyword
                    if (keyword.isNotEmpty()) {
                        Log.i(TAG, "Heard $keyword")
                        spotter.reset(stream)
                        return@withContext true
                    }
                }
            }
            false
        } finally {
            runCatching { record.stop() }
            record.release()
            stream.release()
            spotter.release()
        }
    }

    private fun config() = KeywordSpotterConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "kws/encoder.int8.onnx",
                decoder = "kws/decoder.int8.onnx",
                joiner = "kws/joiner.int8.onnx",
            ),
            tokens = "kws/tokens.txt",
            modelType = "zipformer2",
            numThreads = 1,
        ),
        keywordsFile = "kws/keywords.txt",
        keywordsScore = 1.5f,
        keywordsThreshold = 0.25f,
        numTrailingBlanks = 1,
    )

    private companion object {
        const val TAG = "WakeWord"
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1_600 // 100 ms
    }
}
