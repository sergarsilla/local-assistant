package dev.localassistant.ui.history

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localassistant.R
import dev.localassistant.history.ConversationSummary
import dev.localassistant.ui.components.ChatMessageList
import dev.localassistant.ui.components.ConfirmDialog
import dev.localassistant.ui.components.EmptyState
import dev.localassistant.ui.components.SectionHeader
import dev.localassistant.ui.theme.AssistantIcons
import dev.localassistant.ui.theme.Spacing

interface HistoryActions {
    fun setQuery(query: String)
    fun open(summary: ConversationSummary)
    fun closeDetail()
    fun continueConversation(summary: ConversationSummary)
    fun requestDelete(summary: ConversationSummary)
    fun requestDeleteAll()
    fun confirmDelete()
    fun cancelDelete()
    fun copy(text: String)
    fun openSettings()
}

@Composable
fun HistoryScreen(state: HistoryUiState, actions: HistoryActions, modifier: Modifier = Modifier) {
    val detail = state.detail
    if (detail != null) {
        BackHandler(onBack = actions::closeDetail)
        DetailScreen(detail, actions, modifier)
    } else {
        ListScreen(state, actions, modifier)
    }
    when (val deletion = state.pendingDeletion) {
        is PendingDeletion.One -> ConfirmDialog(
            title = stringResource(R.string.history_delete_title),
            text = stringResource(R.string.history_delete_confirm, deletion.summary.title),
            confirmLabel = stringResource(R.string.models_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = actions::confirmDelete,
            onDismiss = actions::cancelDelete,
            destructive = true,
        )
        PendingDeletion.All -> ConfirmDialog(
            title = stringResource(R.string.history_delete_all),
            text = stringResource(R.string.history_delete_all_confirm),
            confirmLabel = stringResource(R.string.models_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = actions::confirmDelete,
            onDismiss = actions::cancelDelete,
            destructive = true,
        )
        null -> Unit
    }
}

@Composable
private fun ListScreen(state: HistoryUiState, actions: HistoryActions, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.xl)) {
        item {
            Column(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineMedium)
                if (!state.saving) SavingOffNotice(actions::openSettings)
                if (state.hasConversations) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = actions::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.history_search_hint)) },
                    )
                }
            }
        }
        if (!state.hasConversations) {
            item {
                EmptyState(
                    icon = AssistantIcons.Chat,
                    title = stringResource(R.string.history_empty_title),
                    body = stringResource(R.string.history_empty),
                )
            }
        } else if (state.groups.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.history_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.screen),
                )
            }
        }
        state.groups.forEach { group ->
            item(key = "s:${group.section}") { SectionHeader(stringResource(group.section.label())) }
            items(group.conversations, key = { it.id }) { conversation -> ConversationRow(conversation, actions) }
        }
        if (state.hasConversations) {
            item {
                TextButton(onClick = actions::requestDeleteAll, modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.l)) {
                    Text(stringResource(R.string.history_delete_all), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            Text(
                stringResource(R.string.history_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.screen),
            )
        }
    }
}

@Composable
private fun SavingOffNotice(onOpenSettings: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = Spacing.l, end = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.history_saving_off), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.tab_settings)) }
        }
    }
}

@Composable
private fun ConversationRow(conversation: ConversationSummary, actions: HistoryActions) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { actions.open(conversation) },
        headlineContent = { Text(conversation.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                "${DateUtils.getRelativeTimeSpanString(conversation.startedAt)} · " +
                    pluralStringResource(R.plurals.history_messages, conversation.messageCount, conversation.messageCount),
            )
        },
        leadingContent = { Icon(AssistantIcons.Chat, contentDescription = null) },
    )
}

@Composable
private fun DetailScreen(detail: ConversationDetail, actions: HistoryActions, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::closeDetail) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.history_back))
            }
            Text(
                detail.summary.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { actions.requestDelete(detail.summary) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.models_delete))
            }
        }
        ChatMessageList(
            messages = detail.messages,
            state = rememberLazyListState(),
            copyLabel = stringResource(R.string.panel_copy),
            onCopy = actions::copy,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.s),
        )
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { actions.continueConversation(detail.summary) },
                modifier = Modifier.fillMaxWidth().padding(Spacing.l),
            ) {
                Icon(AssistantIcons.Sparkle, contentDescription = null)
                Text(stringResource(R.string.history_continue), modifier = Modifier.padding(start = Spacing.s))
            }
        }
    }
}

private fun DaySection.label(): Int = when (this) {
    DaySection.TODAY -> R.string.history_today
    DaySection.YESTERDAY -> R.string.history_yesterday
    DaySection.THIS_WEEK -> R.string.history_this_week
    DaySection.EARLIER -> R.string.history_earlier
}
