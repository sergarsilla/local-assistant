package dev.localassistant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.localassistant.llm.Role
import dev.localassistant.llm.UiMessage
import dev.localassistant.ui.markdown.MarkdownText
import dev.localassistant.ui.theme.AssistantIcons
import dev.localassistant.ui.theme.Spacing

/**
 * A conversation as a scrolling list: the user's turns as bubbles, the assistant's as text.
 * [copyLastOnly] keeps the copy button to the latest reply, for places short on room.
 */
@Composable
fun ChatMessageList(
    messages: List<UiMessage>,
    state: LazyListState,
    copyLabel: String,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    copyLastOnly: Boolean = false,
) {
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        itemsIndexed(messages, key = { index, message -> "$index:${message.role}" }) { index, message ->
            val copy = onCopy.takeIf { !copyLastOnly || index == messages.lastIndex }
            ChatMessage(message, copyLabel = copyLabel, onCopy = copy)
        }
    }
}

@Composable
fun ChatMessage(message: UiMessage, copyLabel: String, onCopy: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    if (message.role == Role.USER) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                SelectionContainer {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
                    )
                }
            }
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            when {
                message.text.isEmpty() && message.streaming -> TypingIndicator(Modifier.padding(vertical = Spacing.s))
                // Parsing the Markdown again for every token costs more with each one, and it is
                // what makes a streaming reply stutter. The finished reply gets the real rendering.
                message.streaming -> Text(message.text, style = MaterialTheme.typography.bodyLarge)
                else -> SelectionContainer { MarkdownText(message.text) }
            }
            if (onCopy != null && !message.streaming && message.text.isNotEmpty()) {
                IconButton(onClick = { onCopy(message.text) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        AssistantIcons.ContentCopy,
                        contentDescription = copyLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
