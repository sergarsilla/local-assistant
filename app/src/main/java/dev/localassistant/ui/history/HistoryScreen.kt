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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import dev.localassistant.ui.components.CardNote
import dev.localassistant.ui.components.ChatMessageList
import dev.localassistant.ui.components.ConfirmDialog
import dev.localassistant.ui.components.EmptyState
import dev.localassistant.ui.components.GroupCard
import dev.localassistant.ui.components.MetaText
import dev.localassistant.ui.components.ScreenHeader
import dev.localassistant.ui.theme.AssistantIcons
import dev.localassistant.ui.theme.LargeTouch
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

/**
 * Past conversations, newest first, in a card per day bucket: the same shape a chat app uses, so
 * "what did I ask yesterday" is one glance rather than a scroll.
 */
@Composable
private fun ListScreen(state: HistoryUiState, actions: HistoryActions, modifier: Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item {
            ScreenHeader(stringResource(R.string.history_title), subtitle = stringResource(R.string.history_subtitle))
        }
        if (!state.hasConversations) {
            item { EmptyHistory(state.saving, actions::openSettings) }
        } else {
            if (!state.saving) item { SavingOffCard(actions::openSettings) }
            item { SearchField(state.query, actions::setQuery) }
            if (state.groups.isEmpty()) item { NoResults { actions.setQuery("") } }
            state.groups.forEach { group ->
                item(key = "g:${group.section}") {
                    GroupCard(stringResource(group.section.label())) {
                        group.conversations.forEach { conversation -> ConversationRow(conversation, actions) }
                    }
                }
            }
            item { ManageCard(actions::requestDeleteAll) }
        }
    }
}

/** Nothing saved yet. With saving switched off that is the reason, and the fix is one tap away. */
@Composable
private fun EmptyHistory(saving: Boolean, onOpenSettings: () -> Unit) {
    EmptyState(
        icon = AssistantIcons.Chat,
        title = stringResource(R.string.history_empty_title),
        body = stringResource(if (saving) R.string.history_empty else R.string.history_empty_saving_off),
        actionLabel = if (saving) null else stringResource(R.string.tab_settings),
        onAction = if (saving) null else onOpenSettings,
    )
}

@Composable
private fun NoResults(onClear: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.Search,
        title = stringResource(R.string.history_no_results),
        body = stringResource(R.string.history_no_results_body),
        actionLabel = stringResource(R.string.history_clear_search),
        onAction = onClear,
    )
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.history_search_hint)) },
    )
}

@Composable
private fun SavingOffCard(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.history_saving_off),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.tab_settings)) }
        }
    }
}

/** Where the conversations live and the one way to empty it, kept apart from the list itself. */
@Composable
private fun ManageCard(onDeleteAll: () -> Unit) {
    GroupCard(stringResource(R.string.history_manage), icon = AssistantIcons.History) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onDeleteAll),
            leadingContent = {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            headlineContent = {
                Text(stringResource(R.string.history_delete_all), color = MaterialTheme.colorScheme.error)
            },
        )
        CardNote(stringResource(R.string.history_note))
    }
}

@Composable
private fun ConversationRow(conversation: ConversationSummary, actions: HistoryActions) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { actions.open(conversation) },
        headlineContent = { Text(conversation.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            MetaText(
                DateUtils.getRelativeTimeSpanString(conversation.startedAt).toString() + " · " +
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
                modifier = Modifier.fillMaxWidth().padding(Spacing.l).height(LargeTouch),
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
