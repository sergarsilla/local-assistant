package dev.localassistant.ui.home

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.localassistant.R
import dev.localassistant.system.SystemStatus
import dev.localassistant.ui.components.OnResume
import dev.localassistant.ui.components.rememberPermissionRequest
import dev.localassistant.ui.components.rememberWithNotifications
import dev.localassistant.ui.navigation.Destination

@Composable
fun HomeRoute(
    modifier: Modifier,
    onNavigate: (Destination) -> Unit,
    showMessage: (String) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val askMic = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO, viewModel::refresh)
    val withNotifications = rememberWithNotifications()

    OnResume(viewModel::refresh)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { event ->
            showMessage(
                when (event) {
                    is HomeEvent.NotEnoughSpace -> context.getString(
                        R.string.error_not_enough_space,
                        android.text.format.Formatter.formatShortFileSize(context, event.neededBytes),
                    )
                    HomeEvent.NotTheAssistant -> context.getString(R.string.error_not_assistant)
                },
            )
        }
    }

    val actions = remember(viewModel) {
        object : HomeActions {
            override fun fix(step: StepId) {
                when (step) {
                    StepId.ASSISTANT -> context.startActivity(SystemStatus.assistantSettings)
                    StepId.MICROPHONE -> askMic()
                    StepId.SPEECH_MODEL -> withNotifications(viewModel::downloadSpeechModel)
                    StepId.LANGUAGE_MODEL -> withNotifications(viewModel::downloadLanguageModel)
                    StepId.VOICE -> withNotifications(viewModel::downloadVoice)
                    StepId.SCREEN -> context.startActivity(SystemStatus.assistSettings)
                    StepId.KEYBOARD -> context.startActivity(SystemStatus.keyboardSettings)
                }
            }

            override fun retry(step: StepId) = viewModel.retry(step)
            override fun tryAssistant() = viewModel.tryAssistant()
            override fun openModels() = onNavigate(Destination.MODELS)
        }
    }
    HomeScreen(state, actions, modifier)
}
