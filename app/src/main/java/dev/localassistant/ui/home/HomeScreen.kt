package dev.localassistant.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import dev.localassistant.ui.components.SectionHeader
import dev.localassistant.ui.components.displayModelName
import dev.localassistant.ui.components.formatSize
import dev.localassistant.ui.theme.AssistantIcons
import dev.localassistant.ui.theme.Spacing

interface HomeActions {
    fun fix(step: StepId)
    fun retry(step: StepId)
    fun tryAssistant()
    fun openModels()
}

@Composable
fun HomeScreen(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.xl)) {
        item {
            Column(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.l)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            if (state.ready) ReadyCard(state.activeModel, actions::tryAssistant, actions::openModels) else ProgressCard(state)
        }
        item { SectionHeader(stringResource(R.string.home_required)) }
        items(state.required, key = { it.id }) { step -> StepRow(step, actions) }
        item { SectionHeader(stringResource(R.string.home_optional)) }
        items(state.optional, key = { it.id }) { step -> StepRow(step, actions) }
        item { TipsCard() }
    }
}

@Composable
private fun ReadyCard(activeModel: String?, onTry: () -> Unit, onOpenModels: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Icon(
                AssistantIcons.Sparkle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp),
            )
            Text(
                stringResource(R.string.home_ready_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(R.string.home_ready_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            activeModel?.let {
                Text(
                    stringResource(R.string.home_ready_model, displayModelName(it)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button(onClick = onTry) { Text(stringResource(R.string.home_try)) }
                TextButton(onClick = onOpenModels) { Text(stringResource(R.string.home_change_model)) }
            }
        }
    }
}

@Composable
private fun ProgressCard(state: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(stringResource(R.string.home_setup_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.home_setup_progress, state.requiredDone, state.required.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { if (state.required.isEmpty()) 0f else state.requiredDone.toFloat() / state.required.size },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StepRow(step: SetupStep, actions: HomeActions) {
    val texts = step.texts()
    val size = formatSize(step.sizeBytes)
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { StepIcon(step.state) },
        headlineContent = { Text(texts.title) },
        supportingContent = {
            when (val state = step.state) {
                is StepState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.home_downloading, formatSize(state.doneBytes), formatSize(state.totalBytes)))
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                }
                is StepState.Failed -> Text(
                    stringResource(R.string.models_transfer_failed, state.message),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(if (step.sizeBytes > 0 && step.state == StepState.Todo) "${texts.body} · $size" else texts.body)
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
            is StepState.Downloading -> CircularProgressIndicator(progress = { state.fraction }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            is StepState.Failed -> Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            StepState.Todo -> Surface(
                shape = CircleShape,
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.size(22.dp),
            ) {}
        }
    }
}

@Composable
private fun TipsCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.l),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(R.string.home_tips_title), style = MaterialTheme.typography.titleMedium)
            listOf(
                R.string.home_tip_open,
                R.string.home_tip_actions,
                R.string.home_tip_screen,
                R.string.home_tip_dictation,
            ).forEach { tip ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(tip), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
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
        stringResource(R.string.step_model_body),
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
