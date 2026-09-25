package dev.localassistant.wake

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phrases the bundled keyword spotter knows. It is an English model, so the phrases are English;
 * [tokens] are its BPE pieces, produced with the model's own `bpe.model`.
 */
enum class WakePhrase(val label: String, val tokens: String) {
    HEY_COMPUTER("Hey computer", "▁HE Y ▁COMP U TER @HEY_COMPUTER"),
    OKAY_COMPUTER("Okay computer", "▁OKAY ▁COMP U TER @OKAY_COMPUTER"),
    HEY_ASSISTANT("Hey assistant", "▁HE Y ▁AS S IST ANT @HEY_ASSISTANT"),
}

data class WakeWordPreferences(val enabled: Boolean = false, val phrase: WakePhrase = WakePhrase.HEY_COMPUTER)

/**
 * Whether hands-free activation is on, plus a pause switch the assistant panel uses: while the
 * panel listens, the spotter lets go of the microphone.
 */
class WakeWordSettings(context: Context) {
    private val prefs = context.getSharedPreferences("wake_word", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        WakeWordPreferences(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            phrase = prefs.getString(KEY_PHRASE, null)
                ?.let { name -> WakePhrase.entries.firstOrNull { it.name == name } }
                ?: WakePhrase.HEY_COMPUTER,
        ),
    )
    val state: StateFlow<WakeWordPreferences> = _state.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun update(transform: (WakeWordPreferences) -> WakeWordPreferences) {
        val next = transform(_state.value)
        prefs.edit().putBoolean(KEY_ENABLED, next.enabled).putString(KEY_PHRASE, next.phrase.name).apply()
        _state.value = next
    }

    fun setPaused(value: Boolean) {
        _paused.value = value
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PHRASE = "phrase"
    }
}
