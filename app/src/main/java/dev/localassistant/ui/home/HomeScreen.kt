package dev.localassistant.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.localassistant.R
import dev.localassistant.models.Catalog
import dev.localassistant.ui.components.CardNote
import dev.localassistant.ui.components.GroupCard
import dev.localassistant.ui.components.ScreenHeader
import dev.localassistant.ui.components.animatedProgress
import dev.localassistant.ui.components.displayModelName
import dev.localassistant.ui.components.formatSize
import dev.localassistant.ui.theme.AssistantIcons
import dev.localassistant.ui.theme.LargeTouch
import dev.localassistant.ui.theme.Spacing

interface HomeActions {
    fun fix(step: StepId)
    fun retry(step: StepId)
    fun tryAssistant()
    fun openModels()
}

/**
 * First run and daily landing in one screen. The top card always answers "what now?" — the next
 * unfinished step while there is one, the way to open the assistant once there is not.
 */
@Composable
fun HomeScreen(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item {
            ScreenHeader(stringResource(R.string.app_name), subtitle = stringResource(R.string.home_subtitle))
        }
        item {
            if (state.ready) {
                ReadyCard(state.activeModel, actions::tryAssistant, actions::openModels)
            } else {
                SetupCard(state, actions)
            }
        }
        item { StepGroup(stringResource(R.string.home_required), state.required, actions) }
        item { StepGroup(stringResource(R.string.home_optional), state.optional, actions) }
        item { TipsCard() }
    }
}

/** The assistant works: one large button to open it, and the model it will answer with. */
@Composable
private fun ReadyCard(activeModel: String?, onTry: () -> Unit, onOpenModels: () -> Unit) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Icon(AssistantIcons.Sparkle, contentDescription = null, tint = onContainer, modifier = Modifier.size(32.dp))
            Text(stringResource(R.string.home_ready_title), style = MaterialTheme.typography.headlineSmall, color = onContainer)
            Text(stringResource(R.string.home_ready_body), style = MaterialTheme.typography.bodyMedium, color = onContainer)
            activeModel?.let {
                Text(
                    stringResource(R.string.home_ready_model, displayModelName(it)),
                    style = MaterialTheme.typography.labelLarge,
                    color = onContainer,
                )
            }
            Button(onClick = onTry, modifier = Modifier.fillMaxWidth().height(LargeTouch)) {
                Text(stringResource(R.string.home_try), style = MaterialTheme.typography.titleMedium)
            }
            TextButton(
                onClick = onOpenModels,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = onContainer),
            ) { Text(stringResource(R.string.home_change_model)) }
        }
    }
}

/** Setup still unfinished: how far along, and the next step as the only button on the card. */
@Composable
private fun SetupCard(state: HomeUiState, actions: HomeActions) {
    val next = state.required.firstOrNull { it.state != StepState.Done }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.padding(Spacing.xl).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                ProgressRing(state.requiredDone, state.required.size)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(stringResource(R.string.home_setup_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.home_setup_progress, state.requiredDone, state.required.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            next?.let { step ->
                val texts = step.texts()
                Text(
                    stringResource(R.string.home_next, texts.title),
                    style = MaterialTheme.typography.titleMedium,
                )
                when (val stepState = step.state) {
                    is StepState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        LinearProgressIndicator(animatedProgress(stepState.fraction), modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                R.string.home_downloading,
                                formatSize(stepState.doneBytes),
                                formatSize(stepState.totalBytes),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is StepState.Failed -> Button(
                        onClick = { actions.retry(step.id) },
                        modifier = Modifier.fillMaxWidth().height(LargeTouch),
                    ) { Text(stringResource(R.string.models_retry), style = MaterialTheme.typography.titleMedium) }
                    else -> Button(
                        onClick = { actions.fix(step.id) },
                        modifier = Modifier.fillMaxWidth().height(LargeTouch),
                    ) { Text(texts.action, style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

/** How much of the setup is done, as a figure and as an arc that advances when a step lands. */
@Composable
private fun ProgressRing(done: Int, total: Int) {
    val fraction = if (total == 0) 0f else done.toFloat() / total
    Box(Modifier.size(RingSize), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = animatedProgress(fraction),
            modifier = Modifier.size(RingSize),
            strokeWidth = 5.dp,
        )
        Text(stringResource(R.string.home_progress_fraction, done, total), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A group of setup steps. Once every step in it is done it shrinks to a single line: finished
 * work should not keep asking to be read.
 */
@Composable
private fun StepGroup(title: String, steps: List<SetupStep>, actions: HomeActions) {
    GroupCard(title) {
        if (steps.isNotEmpty() && steps.all { it.state == StepState.Done }) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { StepIcon(StepState.Done) },
                headlineContent = {
                    Text(stringResource(R.string.home_all_done), color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
        } else {
            steps.forEach { step -> StepRow(step, actions) }
        }
    }
}

@Composable
private fun StepRow(step: SetupStep, actions: HomeActions) {
    val texts = step.texts()
    val done = step.state == StepState.Done
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { StepIcon(step.state) },
        headlineContent = {
            Text(texts.title, color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        },
        // A finished step needs no explanation, and its summary is the longest text on the row.
        supportingContent = if (done) {
            null
        } else {
            {
                when (val state = step.state) {
                    is StepState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.home_downloading, formatSize(state.doneBytes), formatSize(state.totalBytes)))
                        LinearProgressIndicator(animatedProgress(state.fraction), modifier = Modifier.fillMaxWidth())
                    }
                    is StepState.Failed -> Text(
                        stringResource(R.string.models_transfer_failed, state.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(if (step.sizeBytes > 0) "${texts.body} · ${formatSize(step.sizeBytes)}" else texts.body)
                }
            }
        },
        trailingContent = {
            when (step.state) {
                StepState.Todo -> FilledTonalButton(onClick = { actions.fix(step.id) }) { Text(texts.action) }
                is StepState.Failed -> OutlinedButton(onClick = { actions.retry(step.id) }) {
                    Text(stringResource(R.string.models_retry))
                }
                else -> Unit
            }
        },
    )
}

@Composable
private fun StepIcon(state: StepState) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when (state) {
            StepState.Done -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            is StepState.Downloading -> CircularProgressIndicator(
                progress = animatedProgress(state.fraction),
                modifier = Modifier.size(24.dp),
                strokeWidth = 3.dp,
            )
            is StepState.Failed -> Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            StepState.Todo -> Surface(
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.size(22.dp),
            ) {}
        }
    }
}

@Composable
private fun TipsCard() {
    GroupCard(stringResource(R.string.home_tips_title), icon = AssistantIcons.Sparkle) {
        listOf(
            R.string.home_tip_open,
            R.string.home_tip_actions,
            R.string.home_tip_screen,
            R.string.home_tip_dictation,
        ).forEach { tip -> CardNote(stringResource(tip)) }
    }
}

private data class StepTexts(val title: String, val body: String, val action: String)

@Composable
private fun SetupStep.texts(): StepTexts = when (id) {
    StepId.ASSISTANT -> StepTexts(
        stringResource(R.string.step_assistant_title),
        stringResource(R.string.step_assistant_body),
        stringResource(R.string.step_open_settings),
    )
    StepId.MICROPHONE -> StepTexts(
        stringResource(R.string.step_mic_title),
        stringResource(R.string.step_mic_body),
        stringResource(R.string.step_allow),
    )
    StepId.SPEECH_MODEL -> StepTexts(
        stringResource(R.string.step_speech_title),
        stringResource(R.string.step_speech_body),
        stringResource(R.string.models_download),
    )
    StepId.LANGUAGE_MODEL -> StepTexts(
        stringResource(R.string.step_model_title),
        stringResource(R.string.step_model_body, Catalog.default.title),
        stringResource(R.string.models_download),
    )
    StepId.VOICE -> StepTexts(
        stringResource(R.string.step_voice_title),
        stringResource(R.string.step_voice_body),
        stringResource(R.string.models_download),
    )
    StepId.SCREEN -> StepTexts(
        stringResource(R.string.step_screen_title),
        stringResource(R.string.step_screen_body),
        stringResource(R.string.step_open_settings),
    )
    StepId.KEYBOARD -> StepTexts(
        stringResource(R.string.step_keyboard_title),
        stringResource(R.string.step_keyboard_body),
        stringResource(R.string.step_enable),
    )
}

private val RingSize = 56.dp
