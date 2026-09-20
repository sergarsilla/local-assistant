package dev.localassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import dev.localassistant.AssistantRuntime
import dev.localassistant.R
import dev.localassistant.assistant.ScreenContext
import dev.localassistant.models.ModelFormat
import dev.localassistant.llm.ChatError
import dev.localassistant.llm.ChatSession
import dev.localassistant.llm.EngineState
import dev.localassistant.llm.Role
import dev.localassistant.llm.UiMessage
import dev.localassistant.voice.VoiceController
import dev.localassistant.voice.VoiceState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun AssistantPanel(
    chat: ChatSession,
    voice: VoiceController,
    screen: ScreenContext,
    engineState: StateFlow<EngineState>,
    onDismiss: () -> Unit,
    onOpenApp: (tab: Int) -> Unit,
) {
    AssistantTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    // Taps inside the panel never dismiss it; they only silence the voice.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { voice.speaker.stop() },
                    ),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 6.dp,
            ) {
                PanelContent(
                    chat = chat,
                    voice = voice,
                    screen = screen,
                    engineStateFlow = engineState,
                    onOpenApp = onOpenApp,
                    modifier = Modifier.navigationBarsPadding().padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PanelContent(
    chat: ChatSession,
    voice: VoiceController,
    screen: ScreenContext,
    engineStateFlow: StateFlow<EngineState>,
    onOpenApp: (tab: Int) -> Unit,
    modifier: Modifier,
) {
    val messages by chat.messages.collectAsState()
    val generating by chat.generating.collectAsState()
    val error by chat.error.collectAsState()
    val engineState by engineStateFlow.collectAsState()
    val voiceState by voice.state.collectAsState()
    val speaking by voice.speaker.speaking.collectAsState()
    val conversation by voice.conversation.collectAsState()
    val toolActivity by AssistantRuntime.tools.activity.collectAsState()
    val pendingAction by AssistantRuntime.tools.actions.pending.collectAsState()
    val context = LocalContext.current
    val screenText by screen.text.collectAsState()
    var useScreen by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(voice.language) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(generating) {
        if (!generating) AssistantRuntime.tools.clearActivity()
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
    }

    val submit = {
        val question = if (useScreen) screen.withScreen(input, screenText) else input
        // In conversation mode a typed question is answered out loud too.
        if (conversation) voice.ask(question) else chat.send(question)
        input = ""
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EngineStatus(engineState, Modifier.weight(1f))
            if (screenText != null) {
                FilterChip(
                    selected = useScreen,
                    onClick = { useScreen = !useScreen },
                    label = { Text(stringResource(R.string.panel_use_screen)) },
                )
            }
            FilterChip(
                selected = conversation,
                onClick = voice::toggleConversation,
                label = { Text(stringResource(R.string.panel_conversation)) },
            )
            AssistChip(
                onClick = {
                    language = if (language == "es") "en" else "es"
                    voice.setLanguage(language)
                },
                label = { Text(language.uppercase()) },
            )
        }
        if (messages.isEmpty() && error == null && voiceState !is VoiceState.Listening) {
            Text(stringResource(R.string.panel_greeting), style = MaterialTheme.typography.titleMedium)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { MessageRow(it) }
        }
        ChatErrorRow(error, onOpenModels = { onOpenApp(MainActivity.TAB_MODELS) })
        if (AssistantRuntime.models.active?.format == ModelFormat.GGUF) {
            Text(
                stringResource(R.string.panel_no_tools),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (generating) {
            toolActivity?.let { activity ->
                Text(
                    when (activity.name) {
                        "web_search" -> stringResource(R.string.panel_tool_web_search, activity.summary)
                        "read_web_page" -> stringResource(R.string.panel_tool_read_web_page, activity.summary)
                        else -> stringResource(R.string.panel_tool_generic, activity.name, activity.summary)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        pendingAction?.let { action ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(action.label, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { AssistantRuntime.tools.actions.confirm(context) }) {
                            Text(stringResource(R.string.panel_action_confirm))
                        }
                        TextButton(onClick = { AssistantRuntime.tools.actions.clear() }) {
                            Text(stringResource(R.string.panel_action_discard))
                        }
                    }
                }
            }
        }
        VoiceStatusRow(
            state = voiceState,
            speaking = speaking,
            conversationHint = stringResource(R.string.panel_conversation_on).takeIf {
                conversation && !speaking && voiceState is VoiceState.Idle
            },
            onStopSpeaking = voice.speaker::stop,
            onOpenVoiceSetup = { onOpenApp(MainActivity.TAB_SETUP) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { if (it.isFocused) voice.stopListening() }
                    .onPreviewKeyEvent { event ->
                        // Enter sends; Shift+Enter keeps a newline for multi-line prompts.
                        val isEnter = event.key == Key.Enter && !event.isShiftPressed
                        if (isEnter && event.type == KeyEventType.KeyDown && !generating) submit()
                        isEnter
                    },
                placeholder = { Text(stringResource(R.string.panel_hint)) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            when {
                generating -> FilledIconButton(onClick = chat::stop) {
                    Icon(AssistantIcons.Stop, contentDescription = stringResource(R.string.panel_stop))
                }
                input.isNotBlank() -> FilledIconButton(onClick = submit) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.panel_send))
                }
                voiceState is VoiceState.Listening || voiceState is VoiceState.Preparing ->
                    FilledIconButton(onClick = voice::toggleListening) {
                        Icon(AssistantIcons.Mic, contentDescription = stringResource(R.string.panel_stop_listening))
                    }
                else -> FilledTonalIconButton(onClick = voice::toggleListening) {
                    Icon(AssistantIcons.Mic, contentDescription = stringResource(R.string.panel_listen))
                }
            }
        }
    }
}

@Composable
private fun EngineStatus(state: EngineState, modifier: Modifier) {
    val text = when (state) {
        is EngineState.Loading -> stringResource(R.string.panel_loading_model, shortName(state.model))
        is EngineState.Ready -> stringResource(R.string.panel_model_ready, shortName(state.model))
        else -> ""
    }
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ChatErrorRow(error: ChatError?, onOpenModels: () -> Unit) {
    when (error) {
        ChatError.NoModel -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.panel_no_model), modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onOpenModels) { Text(stringResource(R.string.panel_open_models)) }
        }
        is ChatError.Failure -> Text(
            stringResource(R.string.panel_error, error.message),
            color = MaterialTheme.colorScheme.error,
        )
        null -> Unit
    }
}

@Composable
private fun VoiceStatusRow(
    state: VoiceState,
    speaking: Boolean,
    conversationHint: String?,
    onStopSpeaking: () -> Unit,
    onOpenVoiceSetup: () -> Unit,
) {
    when {
        speaking -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.panel_speaking), modifier = Modifier.weight(1f))
            FilledTonalIconButton(onClick = onStopSpeaking) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.panel_stop_speaking))
            }
        }
        state is VoiceState.Listening -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (state.hearingSpeech) R.string.panel_hearing else R.string.panel_listening),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { (state.level * LEVEL_GAIN).coerceIn(0.02f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        conversationHint != null -> Text(conversationHint, style = MaterialTheme.typography.bodyMedium)
        state is VoiceState.Preparing -> Text(stringResource(R.string.panel_listening))
        state is VoiceState.Transcribing -> Text(stringResource(R.string.panel_transcribing))
        state is VoiceState.NeedsPermission || state is VoiceState.NeedsModel -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(
                    if (state is VoiceState.NeedsPermission) R.string.panel_needs_mic else R.string.panel_needs_voice_model,
                ),
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onOpenVoiceSetup) { Text(stringResource(R.string.panel_open_voice_setup)) }
        }
        else -> Spacer(Modifier)
    }
}

@Composable
private fun MessageRow(message: UiMessage) {
    val isUser = message.role == Role.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        val text = message.text.ifEmpty { if (message.streaming) "…" else "" }
        if (isUser) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        } else {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** File names are long and the header is narrow, so the extension and the quantisation go. */
private fun shortName(model: String) = model
    .substringBeforeLast('.')
    .removeSuffix("-it")
    .replace(Regex("-(it|instruct)(-|$)", RegexOption.IGNORE_CASE), "")

// Speech RMS rarely exceeds ~0.2, so it is scaled up to fill the meter.
private const val LEVEL_GAIN = 6f
