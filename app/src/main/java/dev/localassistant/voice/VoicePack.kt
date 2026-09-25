package dev.localassistant.voice

import android.content.Context
import java.io.File

enum class PackKind { SPEECH_TO_TEXT, TEXT_TO_SPEECH }

/**
 * Speech models downloaded as whole Hugging Face repositories of loose files.
 * [speakerId] only matters for multi-speaker models such as Kokoro.
 */
enum class VoicePack(
    val id: String,
    val repo: String,
    val kind: PackKind,
    val approxBytes: Long,
    val language: String? = null,
    val speakerId: Int = 0,
) {
    STT_CANARY(
        "stt-canary", "csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8",
        PackKind.SPEECH_TO_TEXT, 207_000_000,
    ),
    STT_PARAKEET(
        "stt-parakeet", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        PackKind.SPEECH_TO_TEXT, 671_000_000,
    ),
    TTS_ES_DAVEFX("tts-es", "csukuangfj/vits-piper-es_ES-davefx-medium", PackKind.TEXT_TO_SPEECH, 81_000_000, "es"),
    TTS_ES_MIRO("tts-es-miro", "csukuangfj/vits-piper-es_ES-miro-high", PackKind.TEXT_TO_SPEECH, 81_000_000, "es"),
    TTS_ES_SHARVARD(
        "tts-es-sharvard", "csukuangfj/vits-piper-es_ES-sharvard-medium", PackKind.TEXT_TO_SPEECH, 95_000_000, "es",
    ),
    TTS_MX_CLAUDE("tts-mx-claude", "csukuangfj/vits-piper-es_MX-claude-high", PackKind.TEXT_TO_SPEECH, 81_000_000, "es"),
    TTS_AR_DANIELA(
        "tts-ar-daniela", "csukuangfj/vits-piper-es_AR-daniela-high", PackKind.TEXT_TO_SPEECH, 132_000_000, "es",
    ),
    TTS_KOKORO_ES(
        "tts-kokoro-es", "csukuangfj/kokoro-int8-multi-lang-v1_1", PackKind.TEXT_TO_SPEECH, 190_000_000, "es",
        speakerId = 28,
    ),
    TTS_EN_LESSAC("tts-en", "csukuangfj/vits-piper-en_US-lessac-medium", PackKind.TEXT_TO_SPEECH, 81_000_000, "en"),
    TTS_KOKORO_EN(
        "tts-kokoro-en", "csukuangfj/kokoro-int8-multi-lang-v1_1", PackKind.TEXT_TO_SPEECH, 190_000_000, "en",
        speakerId = 2,
    );

    val isKokoro: Boolean get() = "kokoro" in repo

    /** Packs built from the same repository (the two Kokoro voices) share one directory. */
    val storageId: String get() = if (isKokoro) KOKORO_STORAGE else id

    fun directory(context: Context) = File(context.filesDir, "voice/$storageId")

    fun isInstalled(context: Context) = File(directory(context), COMPLETE_MARKER).exists()

    /** Only the files the runtime needs; the repos also carry scripts, test audio and other languages. */
    fun wants(path: String): Boolean {
        val topLevel = '/' !in path
        return when {
            path.startsWith("espeak-ng-data/") -> kind == PackKind.TEXT_TO_SPEECH
            !topLevel -> false
            isKokoro -> path.endsWith(".onnx") || path == "voices.bin" || path == "tokens.txt" ||
                path == "lexicon-us-en.txt"
            else -> path.endsWith(".onnx") || path == "tokens.txt"
        }
    }

    companion object {
        const val COMPLETE_MARKER = ".complete"
        private const val KOKORO_STORAGE = "kokoro-multi-lang"
        /** Null for ids saved by an older version that no longer exist. */
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        fun voicesFor(language: String) = entries.filter { it.kind == PackKind.TEXT_TO_SPEECH && it.language == language }
    }
}
