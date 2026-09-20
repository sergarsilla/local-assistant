package dev.localassistant.voice

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoicePreferences(
    val speechToText: VoicePack,
    val listenOnOpen: Boolean,
    val speakReplies: Boolean,
    val language: String,
    val voices: Map<String, VoicePack>,
) {
    fun voiceFor(language: String): VoicePack? = voices[language]
}

class VoiceSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<VoicePreferences> = _state.asStateFlow()

    fun update(transform: (VoicePreferences) -> VoicePreferences) {
        val next = transform(_state.value)
        prefs.edit()
            .putString(KEY_STT, next.speechToText.id)
            .putBoolean(KEY_LISTEN_ON_OPEN, next.listenOnOpen)
            .putBoolean(KEY_SPEAK, next.speakReplies)
            .putString(KEY_LANGUAGE, next.language)
            .putString(KEY_VOICE_ES, next.voices["es"]?.id)
            .putString(KEY_VOICE_EN, next.voices["en"]?.id)
            .apply()
        _state.value = next
    }

    private fun read() = VoicePreferences(
        speechToText = prefs.getString(KEY_STT, null)?.let(VoicePack::byId)
            ?: VoicePack.STT_PARAKEET.takeIf { it.isInstalled(context) }
            ?: VoicePack.STT_CANARY,
        listenOnOpen = prefs.getBoolean(KEY_LISTEN_ON_OPEN, true),
        speakReplies = prefs.getBoolean(KEY_SPEAK, true),
        language = prefs.getString(KEY_LANGUAGE, null) ?: defaultLanguage(),
        voices = mapOf("es" to KEY_VOICE_ES, "en" to KEY_VOICE_EN).mapNotNull { (language, key) ->
            val pack = prefs.getString(key, null)?.let(VoicePack::byId)
                ?: VoicePack.voicesFor(language).firstOrNull { it == DEFAULT_VOICE && it.isInstalled(context) }
                ?: VoicePack.voicesFor(language).firstOrNull { it.isInstalled(context) }
            pack?.let { language to it }
        }.toMap(),
    )

    private fun defaultLanguage() = if (Locale.getDefault().language == "en") "en" else "es"

    private companion object {
        const val KEY_STT = "stt"
        const val KEY_LISTEN_ON_OPEN = "listen_on_open"
        const val KEY_SPEAK = "speak_replies"
        const val KEY_LANGUAGE = "language"
        val DEFAULT_VOICE = VoicePack.TTS_ES_MIRO
        const val KEY_VOICE_ES = "voice_es"
        const val KEY_VOICE_EN = "voice_en"
    }
}
