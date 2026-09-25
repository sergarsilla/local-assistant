package dev.localassistant.ui.settings

import android.Manifest
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.localassistant.R
import dev.localassistant.llm.Accelerator
import dev.localassistant.system.SystemStatus
import dev.localassistant.ui.components.OnResume
import dev.localassistant.ui.components.rememberPermissionRequest
import dev.localassistant.ui.components.rememberWithNotifications
import dev.localassistant.voice.VoicePack
import dev.localassistant.wake.WakePhrase

@Composable
fun SettingsRoute(
    modifier: Modifier,
    showMessage: (String) -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val askMic = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO, viewModel::refresh)
    val askContacts = rememberPermissionRequest(Manifest.permission.READ_CONTACTS, viewModel::refresh)
    val withNotifications = rememberWithNotifications()

    OnResume(viewModel::refresh)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { event ->
            when (event) {
                is SettingsEvent.NotEnoughSpace -> showMessage(
                    context.getString(R.string.error_not_enough_space, Formatter.formatShortFileSize(context, event.neededBytes)),
                )
            }
        }
    }

    val actions = remember(viewModel) {
        object : SettingsActions {
            override fun openAssistantSettings() = context.startActivity(SystemStatus.assistantSettings)
            override fun openAssistSettings() = context.startActivity(SystemStatus.assistSettings)
            override fun openKeyboardSettings() = context.startActivity(SystemStatus.keyboardSettings)
            override fun requestMicrophone() = askMic()
            override fun requestContacts() = askContacts()
            override fun setListenOnOpen(value: Boolean) = viewModel.setListenOnOpen(value)
            override fun setSpeakReplies(value: Boolean) = viewModel.setSpeakReplies(value)
            override fun setKeyboardListens(value: Boolean) = viewModel.setKeyboardListens(value)
            override fun downloadPack(pack: VoicePack) = withNotifications { viewModel.downloadPack(pack) }
            override fun cancelPackDownload(pack: VoicePack) = viewModel.cancelPackDownload(pack)
            override fun usePack(pack: VoicePack) = viewModel.usePack(pack)
            override fun requestPackDelete(pack: VoicePack) = viewModel.requestPackDelete(pack)
            override fun confirmPackDelete() = viewModel.confirmPackDelete()
            override fun cancelPackDelete() = viewModel.cancelPackDelete()
            override fun setAccelerator(accelerator: Accelerator) = viewModel.setAccelerator(accelerator)
            override fun setUnderstandImages(value: Boolean) = viewModel.setUnderstandImages(value)
            override fun setWebSearch(value: Boolean) = viewModel.setWebSearch(value)
            override fun setSaveHistory(value: Boolean) = viewModel.setSaveHistory(value)
            override fun setWakeWord(value: Boolean) = viewModel.setWakeWord(value)
            override fun setWakePhrase(phrase: WakePhrase) = viewModel.setWakePhrase(phrase)
            override fun setAppLanguage(tag: String) = viewModel.setAppLanguage(tag)
        }
    }
    SettingsScreen(state, actions, modifier)
}
