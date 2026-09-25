package dev.localassistant.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.localassistant.R
import dev.localassistant.history.ConversationSummary
import dev.localassistant.ui.components.OnResume
import dev.localassistant.ui.components.copyReply

@Composable
fun HistoryRoute(
    modifier: Modifier,
    showMessage: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HistoryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnResume(viewModel::refresh)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { event ->
            when (event) {
                HistoryEvent.NotTheAssistant -> showMessage(context.getString(R.string.error_not_assistant))
            }
        }
    }
    val actions = remember(viewModel) {
        object : HistoryActions {
            override fun setQuery(query: String) = viewModel.setQuery(query)
            override fun open(summary: ConversationSummary) = viewModel.open(summary)
            override fun closeDetail() = viewModel.closeDetail()
            override fun continueConversation(summary: ConversationSummary) = viewModel.continueConversation(summary)
            override fun requestDelete(summary: ConversationSummary) = viewModel.requestDelete(summary)
            override fun requestDeleteAll() = viewModel.requestDeleteAll()
            override fun confirmDelete() = viewModel.confirmDelete()
            override fun cancelDelete() = viewModel.cancelDelete()
            override fun copy(text: String) = copyReply(context, text)
            override fun openSettings() = onOpenSettings()
        }
    }
    HistoryScreen(state, actions, modifier)
}
