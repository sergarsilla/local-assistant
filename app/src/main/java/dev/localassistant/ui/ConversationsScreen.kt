package dev.localassistant.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localassistant.AssistantRuntime
import dev.localassistant.R
import dev.localassistant.history.ConversationSummary
import dev.localassistant.llm.Role

@Composable
fun ConversationsScreen(modifier: Modifier = Modifier) {
    val store = AssistantRuntime.history
    val conversations by store.conversations.collectAsState()
    var open by remember { mutableStateOf<ConversationSummary?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { store.refresh() }

    open?.let { conversation ->
        ConversationDetail(conversation, onBack = { open = null }, onDelete = {
            store.delete(conversation.id)
            open = null
        })
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)
        }
        if (conversations.isEmpty()) {
            item { Text(stringResource(R.string.history_empty)) }
        }
        items(conversations, key = { it.id }) { conversation ->
            Card(Modifier.fillMaxWidth().clickable { open = conversation }) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        conversation.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${DateUtils.getRelativeTimeSpanString(conversation.startedAt)} · " +
                            stringResource(R.string.history_messages, conversation.messageCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (conversations.isNotEmpty()) {
            item {
                OutlinedButton(onClick = { confirmDeleteAll = true }) {
                    Text(stringResource(R.string.history_delete_all))
                }
            }
        }
        item {
            Text(
                stringResource(R.string.history_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            text = { Text(stringResource(R.string.history_delete_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteAll()
                    confirmDeleteAll = false
                }) { Text(stringResource(R.string.models_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ConversationDetail(
    conversation: ConversationSummary,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val messages = remember(conversation.id) { AssistantRuntime.history.messages(conversation.id) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.history_back)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text(stringResource(R.string.models_delete)) }
            }
        }
        items(messages.filter { it.role != Role.SYSTEM }) { message ->
            val fromUser = message.role == Role.USER
            Surface(
                color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(message.text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
