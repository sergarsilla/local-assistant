package dev.localassistant.ui.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.localassistant.AssistantRuntime
import dev.localassistant.R
import dev.localassistant.assistant.ScreenContext
import dev.localassistant.llm.ChatSession
import dev.localassistant.ui.components.copyReply
import dev.localassistant.ui.navigation.Destination
import dev.localassistant.voice.VoiceController

/**
 * Connects the stateless [PanelScreen] to the session's chat and voice: collects their state into
 * one [PanelUiState] and turns taps into calls.
 */
@Composable
fun PanelRoute(
    chat: ChatSession,
    voice: VoiceController,
    screen: ScreenContext,
    onDismiss: () -> Unit,
    onOpenApp: (Destination) -> Unit,
) {
    val runtime = AssistantRuntime
    val messages by chat.messages.collectAsState()
    val generating by chat.generating.collectAsState()
    val error by chat.error.collectAsState()
    val engine by runtime.engine.state.collectAsState()
    val voiceState by voice.state.collectAsState()
    val speaking by voice.speaker.speaking.collectAsState()
    val conversationMode by voice.conversation.collectAsState()
    val toolActivity by runtime.tools.activity.collectAsState()
    val pendingAction by runtime.tools.actions.pending.collectAsState()
    val screenText by screen.text.collectAsState()
    val screenImage by screen.image.collectAsState()
    val screenAttached by screen.attach.collectAsState()
    val voicePrefs by runtime.voiceSettings.state.collectAsState()
    val webSearch by runtime.tools.webSearch.collectAsState()

    LaunchedEffect(generating) {
        if (!generating) runtime.tools.clearActivity()
    }

    val screenAvailable = screenText != null || screenImage != null
    val suggestions = suggestions(screenAvailable, webSearch)
    val context = LocalContext.current
    val actions = remember(chat, voice, screen) {
        object : PanelActions {
            override fun send(text: String) {
                // In conversation mode a typed question is answered out loud too.
                if (voice.conversation.value) voice.ask(text) else chat.send(text)
            }

            override fun toggleListening() = voice.toggleListening()
            override fun stopGenerating() = chat.stop()
            override fun stopSpeaking() = voice.speaker.stop()
            override fun toggleConversationMode() = voice.toggleConversation()
            override fun toggleScreen() = screen.setAttach(!screen.attach.value)
            override fun switchLanguage() = voice.setLanguage(if (voice.language == "es") "en" else "es")

            override fun newConversation() {
                voice.speaker.stop()
                chat.clear()
            }

            override fun confirmAction() = runtime.tools.confirmPendingAction()
            override fun discardAction() = runtime.tools.actions.clear()
            override fun copy(text: String) = copyReply(context, text)
            override fun openModels() = onOpenApp(Destination.MODELS)
            override fun openVoiceSettings() = onOpenApp(Destination.SETTINGS)
            override fun dismiss() = onDismiss()
        }
    }

    PanelScreen(
        state = PanelUiState(
            messages = messages,
            generating = generating,
            error = error,
            engine = engine,
            voice = voiceState,
            speaking = speaking,
            conversationMode = conversationMode,
            toolActivity = toolActivity,
            pendingAction = pendingAction,
            screenAvailable = screenAvailable,
            screenAttached = screenAttached,
            language = voicePrefs.language,
            suggestions = suggestions,
        ),
        actions = actions,
    )
}

/** Examples that show what the assistant can do right now, in the app's language. */
@Composable
private fun suggestions(screenAvailable: Boolean, webSearch: Boolean): List<Suggestion> = buildList {
    if (screenAvailable) {
        add(Suggestion(stringResource(R.string.suggest_screen_label), stringResource(R.string.suggest_screen_prompt)))
    }
    add(Suggestion(stringResource(R.string.suggest_timer_label), stringResource(R.string.suggest_timer_prompt)))
    add(Suggestion(stringResource(R.string.suggest_alarm_label), stringResource(R.string.suggest_alarm_prompt)))
    if (webSearch) {
        add(Suggestion(stringResource(R.string.suggest_news_label), stringResource(R.string.suggest_news_prompt)))
    }
    add(Suggestion(stringResource(R.string.suggest_idea_label), stringResource(R.string.suggest_idea_prompt)))
}
