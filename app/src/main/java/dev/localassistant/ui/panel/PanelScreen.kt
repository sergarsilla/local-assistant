package dev.localassistant.ui.panel

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localassistant.R
import dev.localassistant.llm.ChatError
import dev.localassistant.llm.EngineState
import dev.localassistant.tools.ActionKind
import dev.localassistant.tools.PendingAction
import dev.localassistant.tools.ToolActivity
import dev.localassistant.ui.components.ChatMessageList
import dev.localassistant.ui.components.VoiceMode
import dev.localassistant.ui.components.VoiceOrb
import dev.localassistant.ui.components.displayModelName
import dev.localassistant.ui.theme.AssistantIcons
import dev.localassistant.ui.theme.Spacing
import dev.localassistant.voice.VoiceState
import java.util.Locale

/**
 * The assistant as a sheet over whatever app is open, laid out to be used at a glance and with
 * a thumb: the voice button is also the status display. Tapping outside dismisses the sheet;
 * tapping inside only silences the voice, so a stray touch never loses the conversation.
 * [startTyping] opens it with the keyboard composer instead of the voice button.
 */
@Composable
fun PanelScreen(state: PanelUiState, actions: PanelActions, startTyping: Boolean = false) {
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
                    .padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l)
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
                state.pendingAction?.let { PendingActionCard(it, actions) }
                ErrorCard(state.error, onOpenModels = actions::openModels)
                VoiceSetupCard(state.voice, onOpenSettings = actions::openVoiceSettings)
                PanelToggles(state, actions)
                Composer(state, actions, startTyping)
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

/** Only what the sheet itself needs; spaced so "new" and "close" are not mistaken for each other. */
@Composable
private fun PanelHeader(state: PanelUiState, actions: PanelActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
        EngineBadge(state.engine, Modifier.weight(1f))
        if (state.messages.isNotEmpty()) {
            IconButton(onClick = actions::newConversation) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.panel_new_conversation))
            }
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            suggestions.forEach { suggestion ->
                SuggestionChip(
                    onClick = { onSuggestion(suggestion.prompt) },
                    label = { Text(suggestion.label, style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.height(ChipHeight),
                )
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
        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
        copyLastOnly = true,
    )
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

/** The one decision in the panel with consequences, so it gets the largest type and buttons. */
@Composable
private fun PendingActionCard(action: PendingAction, actions: PanelActions) {
    val (icon, title) = when (action.kind) {
        ActionKind.MESSAGE -> Icons.Filled.Email to stringResource(R.string.panel_action_message_title)
        ActionKind.CALENDAR_EVENT -> Icons.Filled.DateRange to stringResource(R.string.panel_action_event_title)
    }
    val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Icon(icon, contentDescription = null, tint = onContainer)
                Text(title, style = MaterialTheme.typography.titleMedium, color = onContainer)
            }
            Text(action.detail, style = MaterialTheme.typography.titleLarge, color = onContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                OutlinedButton(
                    onClick = actions::discardAction,
                    modifier = Modifier.weight(1f).height(LargeTouch),
                    border = BorderStroke(1.dp, onContainer),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = onContainer),
                ) { Text(stringResource(R.string.panel_action_discard), style = MaterialTheme.typography.titleMedium) }
                Button(
                    onClick = actions::confirmAction,
                    modifier = Modifier.weight(1f).height(LargeTouch),
                ) { Text(stringResource(R.string.panel_action_confirm), style = MaterialTheme.typography.titleMedium) }
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
private fun VoiceSetupCard(voice: VoiceState, onOpenSettings: () -> Unit) {
    val text = when (voice) {
        VoiceState.NeedsPermission -> R.string.panel_needs_mic
        VoiceState.NeedsModel -> R.string.panel_needs_voice_model
        else -> return
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onOpenSettings) { Text(stringResource(R.string.panel_open_voice_setup)) }
        }
    }
}

/** Settings worth changing mid-conversation, each with a label so none has to be guessed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelToggles(state: PanelUiState, actions: PanelActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        ToggleChip(
            label = stringResource(R.string.panel_conversation),
            icon = AssistantIcons.GraphicEq,
            selected = state.conversationMode,
            onClick = actions::toggleConversationMode,
        )
        AssistChip(
            onClick = actions::switchLanguage,
            label = { Text(languageName(state.language), style = MaterialTheme.typography.labelLarge) },
            leadingIcon = { Icon(AssistantIcons.Translate, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            modifier = Modifier.height(ChipHeight),
        )
        if (state.screenAvailable) {
            ToggleChip(
                label = stringResource(R.string.panel_use_screen),
                icon = AssistantIcons.Smartphone,
                selected = state.screenAttached,
                onClick = actions::toggleScreen,
            )
        }
    }
}

@Composable
private fun ToggleChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        modifier = Modifier.height(ChipHeight),
    )
}

/** A language's own name for itself, which is how people look for it. */
private fun languageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

/**
 * The voice button with what it is doing written underneath. Typing stays one tap away rather
 * than always on screen, so nothing competes with the button.
 */
@Composable
private fun Composer(state: PanelUiState, actions: PanelActions, startTyping: Boolean) {
    var typing by rememberSaveable { mutableStateOf(startTyping) }
    var input by rememberSaveable { mutableStateOf("") }
    val mode = state.voiceMode
    val listening = state.voice as? VoiceState.Listening
    val caption = voiceCaption(state)
    val orbLabel = voiceActionLabel(mode)
    val captionModifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
    VoiceHaptics(mode)

    if (typing) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        val submit = {
            if (input.isNotBlank()) {
                actions.send(input)
                input = ""
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (mode != VoiceMode.IDLE) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = captionModifier,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
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
                VoiceOrb(
                    mode = mode,
                    level = listening?.level ?: 0f,
                    hearingSpeech = listening?.hearingSpeech == true,
                    contentDescription = orbLabel,
                    onClick = {
                        // Going back to the voice layout is what the microphone is for.
                        if (mode == VoiceMode.IDLE) typing = false
                        onVoiceTap(state, actions)
                    },
                    size = CompactOrbSize,
                )
            }
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FilledTonalIconButton(
                    onClick = { typing = true },
                    modifier = Modifier.align(Alignment.CenterStart).size(LargeTouch),
                ) {
                    Icon(AssistantIcons.Keyboard, contentDescription = stringResource(R.string.panel_type))
                }
                VoiceOrb(
                    mode = mode,
                    level = listening?.level ?: 0f,
                    hearingSpeech = listening?.hearingSpeech == true,
                    contentDescription = orbLabel,
                    onClick = { onVoiceTap(state, actions) },
                    size = OrbSize,
                )
            }
            Text(
                caption,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = captionModifier,
            )
        }
    }
}

/** What a tap on the voice button does depends on what it is showing. */
private fun onVoiceTap(state: PanelUiState, actions: PanelActions) = when (state.voiceMode) {
    // In conversation mode the microphone is already open, so interrupting only silences the reply;
    // toggling would end the conversation.
    VoiceMode.SPEAKING -> if (state.conversationMode) actions.stopSpeaking() else actions.toggleListening()
    VoiceMode.WORKING -> if (state.generating) actions.stopGenerating() else actions.toggleListening()
    VoiceMode.IDLE, VoiceMode.LISTENING -> actions.toggleListening()
}

@Composable
private fun voiceCaption(state: PanelUiState): String = when (state.voiceMode) {
    VoiceMode.SPEAKING -> stringResource(R.string.panel_speaking)
    VoiceMode.WORKING -> when {
        state.voice is VoiceState.Transcribing -> stringResource(R.string.panel_transcribing)
        state.toolActivity != null -> toolLabel(state.toolActivity)
        else -> stringResource(R.string.panel_thinking)
    }
    VoiceMode.LISTENING -> stringResource(
        if ((state.voice as? VoiceState.Listening)?.hearingSpeech == true) R.string.panel_hearing else R.string.panel_listening,
    )
    VoiceMode.IDLE -> stringResource(if (state.conversationMode) R.string.panel_conversation_on else R.string.panel_tap_to_talk)
}

@Composable
private fun voiceActionLabel(mode: VoiceMode): String = stringResource(
    when (mode) {
        VoiceMode.IDLE -> R.string.panel_listen
        VoiceMode.LISTENING -> R.string.panel_stop_listening
        VoiceMode.WORKING -> R.string.panel_stop
        VoiceMode.SPEAKING -> R.string.panel_interrupt
    },
)

/** Marks the moments that matter with a vibration, so they can be felt without looking. */
@Composable
private fun VoiceHaptics(mode: VoiceMode) {
    val haptics = LocalHapticFeedback.current
    val previous = remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        val before = previous.value
        previous.value = mode
        when {
            mode == before -> Unit
            mode == VoiceMode.SPEAKING -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            mode == VoiceMode.LISTENING -> haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
            before == VoiceMode.LISTENING -> haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
        }
    }
}

// Sized for a glance and a thumb, above Material's 48 dp minimum.
private val LargeTouch = 56.dp
private val ChipHeight = 48.dp
private val OrbSize = 136.dp
private val CompactOrbSize = 72.dp
