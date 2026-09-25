package dev.localassistant.voice

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the user picked. Picks are resolved against what is installed at the moment of use, so a
 * freshly downloaded pack works straight away and deleting the chosen one falls back to another.
 */
data class VoicePreferences(
    val speechToText: VoicePack,
    val listenOnOpen: Boolean,
    val speakReplies: Boolean,
    val language: String,
    val voices: Map<String, VoicePack>,
    /** The voice keyboard starts listening as soon as it appears. */
    val keyboardListensOnOpen: Boolean = true,
) {
    /** The recognizer to use: the chosen one if installed, otherwise any installed one. */
    val activeSpeechToText: VoicePack?
        get() {
            val installed = VoicePacks.installed.value
            return speechToText.takeIf { it in installed }
                ?: STT_PREFERENCE.firstOrNull { it in installed }
        }

    fun voiceFor(language: String): VoicePack? {
        val installed = VoicePacks.installed.value
        return voices[language]?.takeIf { it in installed }
            ?: VoicePack.voicesFor(language).firstOrNull { it == DEFAULT_VOICES[language] && it in installed }
            ?: VoicePack.voicesFor(language).firstOrNull { it in installed }
    }

    private companion object {
        val STT_PREFERENCE = listOf(VoicePack.STT_PARAKEET, VoicePack.STT_CANARY)
        val DEFAULT_VOICES = mapOf("es" to VoicePack.TTS_ES_MIRO, "en" to VoicePack.TTS_EN_LESSAC)
    }
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
            .putBoolean(KEY_KEYBOARD_LISTENS, next.keyboardListensOnOpen)
            .apply()
        _state.value = next
    }

    private fun read() = VoicePreferences(
        speechToText = prefs.getString(KEY_STT, null)?.let(VoicePack::byId) ?: VoicePack.STT_PARAKEET,
        listenOnOpen = prefs.getBoolean(KEY_LISTEN_ON_OPEN, true),
        speakReplies = prefs.getBoolean(KEY_SPEAK, true),
        language = prefs.getString(KEY_LANGUAGE, null) ?: defaultLanguage(),
        voices = mapOf("es" to KEY_VOICE_ES, "en" to KEY_VOICE_EN).mapNotNull { (language, key) ->
            prefs.getString(key, null)?.let(VoicePack::byId)?.let { language to it }
        }.toMap(),
        keyboardListensOnOpen = prefs.getBoolean(KEY_KEYBOARD_LISTENS, true),
    )

    private fun defaultLanguage() = if (Locale.getDefault().language == "en") "en" else "es"

    private companion object {
        const val KEY_STT = "stt"
        const val KEY_LISTEN_ON_OPEN = "listen_on_open"
        const val KEY_SPEAK = "speak_replies"
        const val KEY_LANGUAGE = "language"
        const val KEY_VOICE_ES = "voice_es"
        const val KEY_VOICE_EN = "voice_en"
        const val KEY_KEYBOARD_LISTENS = "keyboard_listens_on_open"
    }
}
