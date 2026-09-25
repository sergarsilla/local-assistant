package dev.localassistant.ui.panel

import dev.localassistant.llm.ChatError
import dev.localassistant.llm.EngineState
import dev.localassistant.llm.UiMessage
import dev.localassistant.tools.PendingAction
import dev.localassistant.tools.ToolActivity
import dev.localassistant.voice.VoiceState

/** Everything the assistant panel shows, as one immutable snapshot. */
data class PanelUiState(
    val messages: List<UiMessage> = emptyList(),
    val generating: Boolean = false,
    val error: ChatError? = null,
    val engine: EngineState = EngineState.Idle,
    val voice: VoiceState = VoiceState.Idle,
    val speaking: Boolean = false,
    val conversationMode: Boolean = false,
    val toolActivity: ToolActivity? = null,
    val pendingAction: PendingAction? = null,
    val screenAvailable: Boolean = false,
    val screenAttached: Boolean = false,
    val language: String = "es",
    val suggestions: List<Suggestion> = emptyList(),
) {
    val isEmpty: Boolean get() = messages.isEmpty() && error == null
    val listening: Boolean get() = voice is VoiceState.Listening || voice is VoiceState.Preparing
}

/** A tap-to-ask example shown on an empty panel; [prompt] is what gets sent. */
data class Suggestion(val label: String, val prompt: String)

/** What the user can do in the panel. */
interface PanelActions {
    fun send(text: String)
    fun toggleListening()
    fun stopGenerating()
    fun stopSpeaking()
    fun toggleConversationMode()
    fun toggleScreen()
    fun switchLanguage()
    fun newConversation()
    fun confirmAction()
    fun discardAction()
    fun copy(text: String)
    fun openModels()
    fun openVoiceSettings()
    fun dismiss()
}
