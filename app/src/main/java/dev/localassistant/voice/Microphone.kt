package dev.localassistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** Asks the microphone to throw away the utterance being recorded and start listening afresh. */
class CaptureReset {
    @Volatile
    private var requested = false

    fun request() {
        requested = true
    }

    fun consume(): Boolean = requested.also { requested = false }
}

sealed interface ListenEvent {
    data class Level(val rms: Float) : ListenEvent
    data object SpeechStarted : ListenEvent
    /** The utterance ended after enough trailing silence; samples include a short lead-in. */
    data class Utterance(val samples: FloatArray, val peakLevel: Float) : ListenEvent
    data object NoSpeech : ListenEvent
}

/**
 * Records 16 kHz mono audio and uses Silero VAD to find where one utterance starts and ends.
 * Collecting starts the microphone; cancelling the collector stops it.
 */
class Microphone(private val context: Context) {

    @SuppressLint("MissingPermission") // Callers check RECORD_AUDIO before listening.
    /**
     * @param continuous keeps listening after each utterance instead of stopping at the first one.
     * @param strictStart while it returns true (the assistant is speaking), speech must last longer
     *   before it counts, so a cough or a burst of noise does not interrupt the reply.
     */
    fun listen(
        recordingContext: Context = context,
        stopRequested: StateFlow<Boolean>? = null,
        continuous: Boolean = false,
        strictStart: () -> Boolean = { false },
        cancelEcho: Boolean = false,
        reset: CaptureReset? = null,
        // Measured on a Pixel 10: the communication source attenuates speech ~5x and clips the
        // first word, so recognition plus the echo canceller is the only usable pair.
        source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    ): Flow<ListenEvent> = flow {
        val vad = Vad(
            assetManager = context.assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.2f,
                    windowSize = WINDOW,
                    maxSpeechDuration = MAX_UTTERANCE_SECONDS.toFloat(),
                ),
                sampleRate = SpeechToText.SAMPLE_RATE,
            ),
        )
        val minBuffer = AudioRecord.getMinBufferSize(
            SpeechToText.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord.Builder()
            .setContext(recordingContext)
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SpeechToText.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, WINDOW * 2 * 8))
            .build()
        // VOICE_COMMUNICATION plus this canceller keeps the assistant from hearing its own voice.
        val echoCanceller = if (cancelEcho && AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        } else {
            null
        }
        try {
            record.startRecording()
            val pcm = ShortArray(WINDOW)
            val window = FloatArray(WINDOW)
            val utterance = ArrayList<FloatArray>()
            val leadIn = ArrayDeque<FloatArray>()
            var speaking = false
            var silentWindows = 0
            var speechWindows = 0
            var windows = 0
            var utterancePeak = 0f
            while (currentCoroutineContext().isActive) {
                val read = record.read(pcm, 0, WINDOW)
                if (read <= 0) continue
                if (reset?.consume() == true) {
                    vad.reset()
                    utterance.clear()
                    leadIn.clear()
                    utterancePeak = 0f
                    speaking = false
                    speechWindows = 0
                    silentWindows = 0
                    continue
                }
                var sum = 0.0
                for (i in 0 until WINDOW) {
                    window[i] = pcm[i] / 32768f
                    sum += window[i] * window[i]
                }
                windows++
                val level = sqrt(sum / WINDOW).toFloat()
                if (windows % LEVEL_EVERY_WINDOWS == 0) emit(ListenEvent.Level(level))

                // The phone mic is quiet (peaks near 0.03), so the detector gets an amplified copy.
                vad.acceptWaveform(FloatArray(WINDOW) { (window[it] * VAD_GAIN).coerceIn(-1f, 1f) })
                while (!vad.empty()) vad.pop() // Only the speech/no-speech flag is used.
                val isSpeech = vad.isSpeechDetected()
                if (windows % LOG_EVERY_WINDOWS == 0) {
                    Log.i(
                        TAG,
                        "speech=$isSpeech inUtterance=$speaking speechWindows=$speechWindows " +
                            "silentWindows=$silentWindows level=%.3f".format(level),
                    )
                }
                val chunk = window.copyOf()

                if (!speaking) {
                    leadIn.addLast(chunk)
                    if (leadIn.size > LEAD_IN_WINDOWS) leadIn.removeFirst()
                    speechWindows = if (isSpeech) speechWindows + 1 else 0
                    val needed = if (strictStart()) STRICT_START_WINDOWS else START_WINDOWS
                    if (speechWindows >= needed) {
                        speaking = true
                        utterance.addAll(leadIn)
                        emit(ListenEvent.SpeechStarted)
                    } else if (stopRequested?.value == true ||
                        windows > (if (continuous) CONVERSATION_IDLE_WINDOWS else NO_SPEECH_TIMEOUT_WINDOWS)
                    ) {
                        emit(ListenEvent.NoSpeech)
                        return@flow
                    }
                    continue
                }

                utterance.add(chunk)
                utterancePeak = maxOf(utterancePeak, level)
                silentWindows = if (isSpeech) 0 else silentWindows + 1
                if (stopRequested?.value == true ||
                    silentWindows >= END_SILENCE_WINDOWS ||
                    utterance.size >= MAX_UTTERANCE_WINDOWS
                ) {
                    emit(ListenEvent.Utterance(concat(utterance), utterancePeak))
                    if (!continuous) return@flow
                    // Without this the detector can stay convinced the previous turn is still going.
                    vad.reset()
                    utterance.clear()
                    utterancePeak = 0f
                    leadIn.clear()
                    speaking = false
                    speechWindows = 0
                    silentWindows = 0
                    windows = 0
                }
            }
        } finally {
            echoCanceller?.release()
            record.stop()
            record.release()
            vad.release()
        }
    }.flowOn(Dispatchers.IO)

    private fun concat(chunks: List<FloatArray>): FloatArray {
        val out = FloatArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    private companion object {
        const val WINDOW = 512 // 32 ms, the window Silero expects at 16 kHz.
        const val TAG = "Microphone"
        const val VAD_GAIN = 6f
        const val LEVEL_EVERY_WINDOWS = 2
        const val LOG_EVERY_WINDOWS = 31 // ~1 s
        const val START_WINDOWS = 2 // ~64 ms of speech starts an utterance.
        const val STRICT_START_WINDOWS = 14 // ~450 ms while the assistant is talking.
        const val LEAD_IN_WINDOWS = 20 // ~0.64 s kept from before the VAD fires.
        const val END_SILENCE_WINDOWS = 25 // ~0.8 s of silence ends the utterance.
        const val NO_SPEECH_TIMEOUT_WINDOWS = 250 // ~8 s without speech gives up.
        const val CONVERSATION_IDLE_WINDOWS = 2800 // ~90 s of silence closes the conversation.
        const val MAX_UTTERANCE_SECONDS = 30
        const val MAX_UTTERANCE_WINDOWS = MAX_UTTERANCE_SECONDS * 16_000 / WINDOW
    }
}
