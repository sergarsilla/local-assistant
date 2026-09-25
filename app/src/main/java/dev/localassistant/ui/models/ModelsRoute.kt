package dev.localassistant.ui.models

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.localassistant.R
import dev.localassistant.models.RemoteModelFile
import dev.localassistant.system.SystemStatus
import dev.localassistant.ui.components.OnResume
import dev.localassistant.ui.components.rememberWithNotifications

@Composable
fun ModelsRoute(
    modifier: Modifier,
    showMessage: (String) -> Unit,
    viewModel: ModelsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val withNotifications = rememberWithNotifications()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }

    OnResume(viewModel::refresh)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { event ->
            showMessage(
                when (event) {
                    is ModelsEvent.NotEnoughSpace -> context.getString(
                        R.string.error_not_enough_space,
                        Formatter.formatShortFileSize(context, event.neededBytes),
                    )
                    is ModelsEvent.Unsupported -> context.getString(R.string.models_import_unsupported, event.fileName)
                },
            )
        }
    }

    val actions = remember(viewModel) {
        object : ModelsActions {
            override fun select(name: String) = viewModel.select(name)
            override fun requestDelete(name: String) = viewModel.requestDelete(name)
            override fun confirmDelete() = viewModel.confirmDelete()
            override fun cancelDelete() = viewModel.cancelDelete()
            override fun download(file: RemoteModelFile) = withNotifications { viewModel.download(file) }
            override fun cancelTransfer(fileName: String) = viewModel.cancelTransfer(fileName)
            override fun retryTransfer(fileName: String) = viewModel.retryTransfer(fileName)
            override fun importFile() = picker.launch(arrayOf("*/*"))
            override fun setQuery(query: String) = viewModel.setQuery(query)
            override fun search() = viewModel.search()
            override fun toggleRepo(repo: String) = viewModel.toggleRepo(repo)
            override fun openNetworkSettings() = context.startActivity(SystemStatus.appDetails(context))
        }
    }
    ModelsScreen(state, actions, modifier)
}
