package dev.localassistant.ui.panel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localassistant.R
import dev.localassistant.llm.ChatError
import dev.localassistant.llm.EngineState
import dev.localassistant.tools.ActionKind
import dev.localassistant.tools.PendingAction
import dev.localassistant.tools.ToolActivity
import dev.localassistant.ui.components.ChatMessageList
import dev.localassistant.ui.components.displayModelName
import dev.localassistant.ui.components.VoiceLevelRing
import dev.localassistant.ui.theme.AssistantIcons
import dev.localassistant.ui.theme.Spacing
import dev.localassistant.voice.VoiceState

/**
 * The assistant as a sheet over whatever app is open. Tapping outside dismisses it; tapping
 * inside only silences the voice, so a stray touch never loses the conversation.
 */
@Composable
fun PanelScreen(state: PanelUiState, actions: PanelActions) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = actions::dismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (state.speaking) actions.stopSpeaking()
                },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 2.dp,
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.m)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                DragHandle()
                PanelHeader(state, actions)
                if (state.isEmpty) {
                    Greeting(state.suggestions, onSuggestion = actions::send)
                } else {
                    Conversation(state, actions)
                }
                ToolActivityRow(state.toolActivity.takeIf { state.generating })
                state.pendingAction?.let { PendingActionCard(it, actions) }
                ErrorCard(state.error, onOpenModels = actions::openModels)
                VoiceStatus(state, actions)
                Composer(state, actions)
            }
        }
    }
}

@Composable
private fun DragHandle() {
    Box(Modifier.fillMaxWidth().padding(top = Spacing.s), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape),
        )
    }
}

@Composable
private fun PanelHeader(state: PanelUiState, actions: PanelActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        EngineBadge(state.engine, Modifier.weight(1f))
        if (state.messages.isNotEmpty()) {
            IconButton(onClick = actions::newConversation) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.panel_new_conversation))
            }
        }
        IconToggleButton(
            checked = state.conversationMode,
            onCheckedChange = { actions.toggleConversationMode() },
            colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(AssistantIcons.GraphicEq, contentDescription = stringResource(R.string.panel_conversation))
        }
        TextButton(onClick = actions::switchLanguage) {
            Text(state.language.uppercase(), style = MaterialTheme.typography.labelLarge)
        }
        IconButton(onClick = actions::dismiss) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.panel_close))
        }
    }
}

@Composable
private fun EngineBadge(engine: EngineState, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        when (engine) {
            is EngineState.Loading -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else -> Icon(
                AssistantIcons.Sparkle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        val label = when (engine) {
            is EngineState.Loading -> stringResource(R.string.panel_loading_model, displayModelName(engine.model))
            is EngineState.Ready -> stringResource(R.string.panel_model_ready, displayModelName(engine.model), engine.backend)
            is EngineState.Failed -> stringResource(R.string.panel_model_failed, displayModelName(engine.model))
            EngineState.NoModel -> stringResource(R.string.panel_no_model_short)
            EngineState.Idle -> stringResource(R.string.app_name)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Greeting(suggestions: List<Suggestion>, onSuggestion: (String) -> Unit) {
    Column(Modifier.padding(vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(stringResource(R.string.panel_greeting), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.panel_greeting_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            suggestions.forEach { suggestion ->
                SuggestionChip(onClick = { onSuggestion(suggestion.prompt) }, label = { Text(suggestion.label) })
            }
        }
    }
}

@Composable
private fun Conversation(state: PanelUiState, actions: PanelActions) {
    val listState = rememberLazyListState()
    val last = state.messages.lastOrNull()
    // Follows the reply as it grows, including every new chunk of the last message.
    LaunchedEffect(state.messages.size, last?.text?.length) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex, Int.MAX_VALUE)
    }
    ChatMessageList(
        messages = state.messages,
        state = listState,
        copyLabel = stringResource(R.string.panel_copy),
        onCopy = actions::copy,
        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
    )
}

@Composable
private fun ToolActivityRow(activity: ToolActivity?) {
    AnimatedVisibility(activity != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        activity ?: return@AnimatedVisibility
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                toolLabel(activity),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun toolLabel(activity: ToolActivity): String {
    val res = when (activity.name) {
        "web_search" -> R.string.panel_tool_web_search
        "read_web_page" -> R.string.panel_tool_read_web_page
        "set_timer" -> R.string.panel_tool_timer
        "set_alarm" -> R.string.panel_tool_alarm
        "open_app" -> R.string.panel_tool_open_app
        "open_maps" -> R.string.panel_tool_maps
        "create_calendar_event" -> R.string.panel_tool_calendar
        "send_message" -> R.string.panel_tool_message
        "call_contact" -> R.string.panel_tool_call
        else -> return activity.name
    }
    return stringResource(res, activity.summary)
}

@Composable
private fun PendingActionCard(action: PendingAction, actions: PanelActions) {
    val (icon, title) = when (action.kind) {
        ActionKind.MESSAGE -> Icons.Filled.Email to stringResource(R.string.panel_action_message_title)
        ActionKind.CALENDAR_EVENT -> Icons.Filled.DateRange to stringResource(R.string.panel_action_event_title)
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(action.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.align(Alignment.End)) {
                TextButton(
                    onClick = actions::discardAction,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                ) { Text(stringResource(R.string.panel_action_discard)) }
                Button(onClick = actions::confirmAction) { Text(stringResource(R.string.panel_action_confirm)) }
            }
        }
    }
}

@Composable
private fun ErrorCard(error: ChatError?, onOpenModels: () -> Unit) {
    error ?: return
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            val text = when (error) {
                ChatError.NoModel -> stringResource(R.string.panel_no_model)
                is ChatError.Failure -> stringResource(R.string.panel_error, error.message)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            if (error == ChatError.NoModel) {
                FilledTonalButton(onClick = onOpenModels) { Text(stringResource(R.string.panel_open_models)) }
            }
        }
    }
}

@Composable
private fun VoiceStatus(state: PanelUiState, actions: PanelActions) {
    val voice = state.voice
    val modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
    when {
        state.speaking -> StatusLine(AssistantIcons.VolumeUp, stringResource(R.string.panel_speaking), modifier) {
            TextButton(onClick = actions::stopSpeaking) { Text(stringResource(R.string.panel_stop_speaking)) }
        }
        voice is VoiceState.Listening -> Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            VoiceLevelRing(voice.level, voice.hearingSpeech, size = 36.dp)
            Text(
                stringResource(if (voice.hearingSpeech) R.string.panel_hearing else R.string.panel_listening),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        voice is VoiceState.Preparing -> StatusLine(AssistantIcons.Mic, stringResource(R.string.panel_listening), modifier)
        voice is VoiceState.Transcribing -> Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.panel_transcribing), style = MaterialTheme.typography.bodyLarge)
        }
        voice is VoiceState.NeedsPermission || voice is VoiceState.NeedsModel -> Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (voice is VoiceState.NeedsPermission) R.string.panel_needs_mic else R.string.panel_needs_voice_model),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = actions::openVoiceSettings) { Text(stringResource(R.string.panel_open_voice_setup)) }
            }
        }
        state.conversationMode -> StatusLine(AssistantIcons.GraphicEq, stringResource(R.string.panel_conversation_on), modifier)
    }
}

@Composable
private fun StatusLine(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Composer(state: PanelUiState, actions: PanelActions) {
    var input by rememberSaveable { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current
    val submit = {
        if (input.isNotBlank()) {
            actions.send(input)
            input = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (state.screenAvailable) {
            FilterChip(
                selected = state.screenAttached,
                onClick = actions::toggleScreen,
                label = { Text(stringResource(R.string.panel_use_screen)) },
                leadingIcon = { Icon(AssistantIcons.Smartphone, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.panel_hint)) },
                shape = RoundedCornerShape(28.dp),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                trailingIcon = if (input.isNotBlank() && !state.generating) {
                    {
                        IconButton(onClick = submit) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.panel_send))
                        }
                    }
                } else {
                    null
                },
            )
            Spacer(Modifier.width(Spacing.xxs))
            MainButton(state) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (state.generating) actions.stopGenerating() else actions.toggleListening()
            }
        }
    }
}

/** The round button: stops an answer while one is written, otherwise starts or stops listening. */
@Composable
private fun MainButton(state: PanelUiState, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp)) {
        val voice = state.voice
        if (voice is VoiceState.Listening) VoiceLevelRing(voice.level, voice.hearingSpeech, size = 76.dp)
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
        ) {
            when {
                state.generating -> Icon(AssistantIcons.Stop, contentDescription = stringResource(R.string.panel_stop))
                state.listening -> Icon(AssistantIcons.Mic, contentDescription = stringResource(R.string.panel_stop_listening))
                else -> Icon(AssistantIcons.Mic, contentDescription = stringResource(R.string.panel_listen))
            }
        }
    }
}
