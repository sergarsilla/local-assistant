package dev.localassistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Speaks a reply while it is still being generated: text is cut into sentences, the next
 * sentence is synthesized while the current one plays, and [stop] silences it immediately.
 */
class Speaker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val voiceFor: (String) -> VoicePack?,
) : AutoCloseable {
    /**
     * Platform echo cancellation only removes playback that goes out on the voice communication
     * path, so conversation mode has to speak through it instead of the media path.
     */
    @Volatile
    var useCommunicationPath = false
        set(value) {
            if (field != value) {
                field = value
                synchronized(this) {
                    track?.release()
                    track = null
                }
            }
        }

    private val voices = mutableMapOf<String, OfflineTts>()
    private var sentences = Channel<String>(Channel.UNLIMITED)
    private var job: Job? = null
    private var track: AudioTrack? = null
    private val pending = StringBuilder()


    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Everything said in the current reply, used to tell real speech from the assistant's echo. */
    @Volatile
    var spokenSoFar: String = ""
        private set

    fun canSpeak(language: String) = packFor(language)?.isInstalled(context) == true

    /** Starts a new spoken reply, interrupting any previous one. */
    @Synchronized
    fun begin(language: String) {
        stop()
        spokenSoFar = ""
        val pack = packFor(language)?.takeIf { it.isInstalled(context) } ?: return
        val queue = Channel<String>(Channel.UNLIMITED).also { sentences = it }
        val audio = Channel<FloatArray>(capacity = 2)
        _speaking.value = true
        val reply = scope.launch(Dispatchers.Default) {
            val tts = synchronized(voices) { voices.getOrPut(pack.id) { load(pack) } }
            val player = launch(Dispatchers.IO) { play(audio, tts.sampleRate()) }
            for (sentence in queue) {
                if (!isActive) break
                val samples = tts.generate(sentence, sid = pack.speakerId).samples
                if (samples.isNotEmpty()) audio.send(samples)
            }
            audio.close()
            player.join()
        }
        job = reply
        // A cancelled earlier reply may finish after this one started; only the current one clears the flag.
        reply.invokeOnCompletion { synchronized(this) { if (job === reply || job == null) _speaking.value = false } }
    }

    @Synchronized
    fun append(text: String) {
        if (job == null) return
        pending.append(text)
        while (true) {
            val end = SENTENCE_END.find(pending) ?: break
            val sentence = pending.substring(0, end.range.last + 1)
            pending.delete(0, end.range.last + 1)
            enqueue(sentence)
        }
    }

    @Synchronized
    fun finish() {
        enqueue(pending.toString())
        pending.clear()
        sentences.close()
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        pending.clear()
        sentences.close()
        track?.let {
            runCatching {
                it.pause()
                it.flush()
            }
        }
        _speaking.value = false
    }

    override fun close() {
        stop()
        voices.values.forEach { it.release() }
        voices.clear()
        track?.release()
        track = null
    }

    private fun enqueue(text: String) {
        val clean = speakable(text)
        if (clean.isNotBlank()) {
            spokenSoFar += " $clean"
            sentences.trySend(clean)
        }
    }

    /**
     * Silences playback at once while checking whether the user really is talking over the reply.
     * Pausing keeps the audio already queued, so [resume] continues exactly where it stopped.
     */
    private suspend fun play(audio: Channel<FloatArray>, sampleRate: Int) {
        val out = trackFor(sampleRate)
        out.play()
        val startedAt = System.currentTimeMillis()
        var frames = 0
        for (samples in audio) {
            var offset = 0
            while (offset < samples.size && currentCoroutineContext().isActive) {
                val written = out.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            frames += samples.size
        }
        // The device's playback head counter is unreliable here, so wait out the audio's own
        // duration instead, ignoring the time spent paused for interruption checks.
        val audioMs = frames * 1000L / sampleRate
        while (currentCoroutineContext().isActive && System.currentTimeMillis() - startedAt < audioMs) {
            delay(DRAIN_POLL_MS)
        }
        Log.i(TAG, "Spoke ${audioMs}ms of audio")
        out.stop()
    }

    private fun trackFor(sampleRate: Int): AudioTrack {
        track?.takeIf { it.sampleRate == sampleRate }?.let { return it }
        track?.release()
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (useCommunicationPath) AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_ASSISTANT,
                    )
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
            .build()
            .also { track = it }
    }

    private fun load(pack: VoicePack): OfflineTts {
        val dir = pack.directory(context)
        val model = if (pack.isKokoro) {
            OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, "model.int8.onnx").path,
                    voices = File(dir, "voices.bin").path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = File(dir, "espeak-ng-data").path,
                    lexicon = File(dir, "lexicon-us-en.txt").path,
                    lang = pack.language.orEmpty(),
                ),
            )
        } else {
            OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = dir.listFiles { f -> f.name.endsWith(".onnx") }.orEmpty().first().path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = File(dir, "espeak-ng-data").path,
                ),
            )
        }
        return OfflineTts(config = OfflineTtsConfig(model = model.apply { numThreads = 2 }))
    }

    private fun packFor(language: String) = voiceFor(language)

    /** Drops markdown and symbols that a voice would read out literally. */
    private fun speakable(text: String) = text
        .replace(Regex("[*_`#>|]"), " ")
        .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("[\\p{So}\\p{Cn}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        const val TAG = "Speaker"
        const val DRAIN_POLL_MS = 50L
        const val DRAIN_STALL_LIMIT_MS = 300L
        // A sentence ends at . ! ? : ; or a newline followed by whitespace, not inside numbers like 3.5.
        val SENTENCE_END = Regex("(?<!\\d)[.!?;:](?=\\s)|\\n")
    }
}
