package dev.localassistant.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localassistant.AssistantRuntime
import dev.localassistant.assistant.AssistantService
import dev.localassistant.history.ConversationSummary
import dev.localassistant.llm.Role
import dev.localassistant.llm.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConversationDetail(val summary: ConversationSummary, val messages: List<UiMessage>)

/** Something that asks the user first: deleting one conversation or all of them. */
sealed interface PendingDeletion {
    data class One(val summary: ConversationSummary) : PendingDeletion
    data object All : PendingDeletion
}

data class HistoryUiState(
    val query: String = "",
    val groups: List<HistoryGroup> = emptyList(),
    val hasConversations: Boolean = false,
    val detail: ConversationDetail? = null,
    val pendingDeletion: PendingDeletion? = null,
    val saving: Boolean = true,
)

sealed interface HistoryEvent {
    data object NotTheAssistant : HistoryEvent
}

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AssistantRuntime.history
    private val query = MutableStateFlow("")
    private val detail = MutableStateFlow<ConversationDetail?>(null)
    private val pendingDeletion = MutableStateFlow<PendingDeletion?>(null)
    private val events = Channel<HistoryEvent>(Channel.BUFFERED)
    val messages: Flow<HistoryEvent> = events.receiveAsFlow()

    val state: StateFlow<HistoryUiState> = combine(store.conversations, query, detail, pendingDeletion, store.enabled) {
            conversations, query, detail, deletion, saving ->
        HistoryUiState(
            query = query,
            groups = HistoryGrouping.group(HistoryGrouping.filter(conversations, query)),
            hasConversations = conversations.isNotEmpty(),
            detail = detail,
            pendingDeletion = deletion,
            saving = saving,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { store.refresh() }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun open(summary: ConversationSummary) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { store.messages(summary.id) }
            detail.value = ConversationDetail(
                summary,
                saved.filter { it.role == Role.USER || it.role == Role.ASSISTANT }.map { UiMessage(it.role, it.text) },
            )
        }
    }

    fun closeDetail() {
        detail.value = null
    }

    fun continueConversation(summary: ConversationSummary) {
        if (!AssistantService.open(conversationId = summary.id)) events.trySend(HistoryEvent.NotTheAssistant)
    }

    fun requestDelete(summary: ConversationSummary) {
        pendingDeletion.value = PendingDeletion.One(summary)
    }

    fun requestDeleteAll() {
        pendingDeletion.value = PendingDeletion.All
    }

    fun cancelDelete() {
        pendingDeletion.value = null
    }

    fun confirmDelete() {
        val deletion = pendingDeletion.value ?: return
        pendingDeletion.value = null
        viewModelScope.launch(Dispatchers.IO) {
            when (deletion) {
                is PendingDeletion.One -> {
                    store.delete(deletion.summary.id)
                    if (detail.value?.summary?.id == deletion.summary.id) detail.value = null
                }
                PendingDeletion.All -> {
                    store.deleteAll()
                    detail.value = null
                }
            }
        }
    }
}
