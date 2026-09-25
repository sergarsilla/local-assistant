package dev.localassistant.ui.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.localassistant.R
import dev.localassistant.ui.dictation.DictationPanel
import dev.localassistant.ui.theme.AssistantIcons
import dev.localassistant.ui.theme.Spacing
import dev.localassistant.voice.DictationState

/** What the voice keyboard can do in the app it types into. */
interface VoiceKeyboardActions {
    fun toggleDictation()
    fun type(text: String)
    fun backspace()
    fun enter()
    fun switchKeyboard()
    fun openSettings()
}

/**
 * A keyboard made of one large microphone plus the few keys dictation still needs: punctuation
 * the recognizer tends to miss, space, delete, enter and a way back to the regular keyboard.
 */
@Composable
fun VoiceKeyboard(state: DictationState, actions: VoiceKeyboardActions, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = Spacing.s, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions::switchKeyboard) {
                    Icon(AssistantIcons.Keyboard, contentDescription = stringResource(R.string.keyboard_switch))
                }
                Text(
                    stringResource(R.string.keyboard_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            DictationPanel(
                state = state,
                onMicClick = actions::toggleDictation,
                onFixSetup = actions::openSettings,
                micSize = 64.dp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                listOf(",", ".", "?", "¿").forEach { symbol -> TextKey(symbol) { actions.type(symbol) } }
                IconKey(
                    AssistantIcons.SpaceBar,
                    stringResource(R.string.keyboard_space),
                    weight = 3f,
                    onClick = { actions.type(" ") },
                )
                IconKey(AssistantIcons.Backspace, stringResource(R.string.keyboard_delete), onClick = actions::backspace)
                IconKey(AssistantIcons.KeyboardReturn, stringResource(R.string.keyboard_enter), onClick = actions::enter)
            }
        }
    }
}

@Composable
private fun RowScope.TextKey(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.weight(1f).height(KEY_HEIGHT)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RowScope.IconKey(icon: ImageVector, description: String, weight: Float = 1.2f, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.weight(weight).height(KEY_HEIGHT)) {
        Icon(icon, contentDescription = description)
    }
}

private val KEY_HEIGHT = 48.dp
