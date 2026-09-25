package dev.localassistant.ui.dictation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.localassistant.R
import dev.localassistant.ui.components.VoiceLevelRing
import dev.localassistant.ui.theme.AssistantIcons
import dev.localassistant.ui.theme.Spacing
import dev.localassistant.voice.DictationState

/**
 * The microphone and what it is doing, shared by the voice keyboard and the dictation dialog.
 * One button does the obvious thing in every state: start, finish now, or try again.
 */
@Composable
fun DictationPanel(
    state: DictationState,
    onMicClick: () -> Unit,
    onFixSetup: () -> Unit,
    modifier: Modifier = Modifier,
    micSize: Dp = 72.dp,
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(micSize + 24.dp)) {
            when (state) {
                is DictationState.Listening -> VoiceLevelRing(state.level, state.hearingSpeech, size = micSize + 24.dp)
                DictationState.Transcribing -> CircularProgressIndicator(Modifier.size(micSize + 12.dp))
                else -> Unit
            }
            FilledIconButton(
                onClick = onMicClick,
                enabled = state != DictationState.Transcribing,
                shape = CircleShape,
                modifier = Modifier.size(micSize),
            ) {
                Icon(
                    if (state is DictationState.Listening) AssistantIcons.Stop else AssistantIcons.Mic,
                    contentDescription = stringResource(
                        if (state is DictationState.Listening) R.string.dictation_done else R.string.panel_listen,
                    ),
                    modifier = Modifier.size(micSize / 2.4f),
                )
            }
        }
        Text(
            statusText(state),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state == DictationState.NeedsPermission || state == DictationState.NeedsModel) {
            FilledTonalButton(onClick = onFixSetup) { Text(stringResource(R.string.dictation_open_app)) }
        }
    }
}

@Composable
private fun statusText(state: DictationState): String = stringResource(
    when (state) {
        DictationState.Idle -> R.string.dictation_tap_to_speak
        is DictationState.Listening -> if (state.hearingSpeech) R.string.panel_hearing else R.string.panel_listening
        DictationState.Transcribing -> R.string.panel_transcribing
        DictationState.NeedsPermission -> R.string.panel_needs_mic
        DictationState.NeedsModel -> R.string.dictation_no_model
        DictationState.NothingHeard -> R.string.dictation_nothing_heard
    },
)
