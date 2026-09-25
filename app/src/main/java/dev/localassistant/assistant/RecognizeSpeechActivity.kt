package dev.localassistant.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.localassistant.AssistantRuntime
import dev.localassistant.R
import dev.localassistant.ui.MainActivity
import dev.localassistant.ui.dictation.DictationPanel
import dev.localassistant.ui.navigation.Destination
import dev.localassistant.ui.theme.AssistantTheme
import dev.localassistant.ui.theme.Spacing
import dev.localassistant.voice.Dictation
import dev.localassistant.voice.DictationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Answers `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, the intent behind the microphone button in
 * search bars, maps and note apps. Without Google's app nothing on GrapheneOS handles it, so those
 * buttons do nothing; this sheet gives them the same offline recognizer as the assistant.
 */
class RecognizeSpeechActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var dictation: Dictation

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else finishWith(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dictation = Dictation(this, scope, AssistantRuntime.speech, AssistantRuntime.voiceSettings)
        setContent {
            AssistantTheme {
                val state by dictation.state.collectAsState()
                DictationSheet(
                    state = state,
                    onMicClick = {
                        if (state is DictationState.Listening) dictation.finishSpeaking() else listen()
                    },
                    onFixSetup = {
                        startActivity(MainActivity.intent(this, Destination.SETTINGS))
                        finishWith(null)
                    },
                    onCancel = { finishWith(null) },
                )
            }
        }
        if (savedInstanceState == null) listen()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun listen() {
        val language = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        dictation.start(language) { text -> finishWith(text) }
        if (dictation.state.value == DictationState.NeedsPermission) {
            askMic.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun finishWith(text: String?) {
        dictation.cancel()
        if (text == null) {
            setResult(RESULT_CANCELED)
        } else {
            setResult(
                RESULT_OK,
                Intent()
                    .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
                    .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(1f)),
            )
        }
        finish()
    }
}

@androidx.compose.runtime.Composable
private fun DictationSheet(
    state: DictationState,
    onMicClick: () -> Unit,
    onFixSetup: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(
                Modifier.navigationBarsPadding().padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.dictation_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DictationPanel(
                    state = state,
                    onMicClick = onMicClick,
                    onFixSetup = onFixSetup,
                    modifier = Modifier.padding(vertical = Spacing.l),
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}
