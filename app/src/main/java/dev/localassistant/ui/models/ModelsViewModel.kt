package dev.localassistant.ui.models

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localassistant.AssistantRuntime
import dev.localassistant.llm.EngineHolder
import dev.localassistant.llm.EngineState
import dev.localassistant.models.Catalog
import dev.localassistant.models.CatalogEntry
import dev.localassistant.models.HuggingFaceClient
import dev.localassistant.models.ModelDownloadService
import dev.localassistant.models.ModelFormat
import dev.localassistant.models.RemoteModelFile
import dev.localassistant.models.RemoteRepo
import dev.localassistant.models.Storage
import dev.localassistant.models.TransferState
import dev.localassistant.models.Transfers
import dev.localassistant.system.SystemStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InstalledModelUi(
    val name: String,
    val sizeBytes: Long,
    val format: ModelFormat,
    val tokensPerSecond: Double?,
    val contextTokens: Int,
    val active: Boolean,
    /** "GPU" or "CPU" while the model is loaded. */
    val runningOn: String?,
)

data class CatalogItemUi(val entry: CatalogEntry, val installed: Boolean, val transfer: TransferState?, val fits: Boolean)

data class SearchUi(
    val query: String = "",
    val loading: Boolean = false,
    val repos: List<RemoteRepo> = emptyList(),
    val expanded: String? = null,
    val files: List<RemoteModelFile>? = null,
    val error: String? = null,
)

data class ModelsUiState(
    val installed: List<InstalledModelUi> = emptyList(),
    val transfers: List<TransferState> = emptyList(),
    val catalog: List<CatalogItemUi> = emptyList(),
    val search: SearchUi = SearchUi(),
    val freeBytes: Long = Long.MAX_VALUE,
    val networkAllowed: Boolean = true,
    val pendingDelete: InstalledModelUi? = null,
)

sealed interface ModelsEvent {
    data class NotEnoughSpace(val neededBytes: Long) : ModelsEvent
    data class Unsupported(val fileName: String) : ModelsEvent
}

class ModelsViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = AssistantRuntime
    private val system = MutableStateFlow(SystemStatus.read(application))
    private val search = MutableStateFlow(SearchUi())
    private val pendingDelete = MutableStateFlow<String?>(null)
    private val events = Channel<ModelsEvent>(Channel.BUFFERED)
    val messages: Flow<ModelsEvent> = events.receiveAsFlow()

    private val installed = combine(runtime.models.installed, runtime.models.activeName, runtime.engine.state) { models, activeName, engine ->
        val active = activeName ?: models.firstOrNull()?.name
        models.map { model ->
            InstalledModelUi(
                name = model.name,
                sizeBytes = model.sizeBytes,
                format = model.format,
                tokensPerSecond = model.tokensPerSecond,
                contextTokens = model.contextTokens ?: EngineHolder.CONTEXT_SIZE,
                active = model.name == active,
                runningOn = (engine as? EngineState.Ready)?.takeIf { it.model == model.name }?.backend,
            )
        }
    }

    val state: StateFlow<ModelsUiState> = combine(installed, Transfers.state, search, system, pendingDelete) {
            models, transfers, search, status, deleting ->
        val names = models.map { it.name }.toSet()
        ModelsUiState(
            installed = models,
            // Voice packs download through the same service; they are shown in Settings.
            transfers = transfers.values.filter { ModelFormat.of(it.fileName) != null },
            catalog = Catalog.recommended.map { entry ->
                CatalogItemUi(
                    entry = entry,
                    installed = entry.file.fileName in names,
                    transfer = transfers[entry.file.fileName],
                    fits = Storage.fits(entry.file.sizeBytes, status.freeBytes),
                )
            },
            search = search,
            freeBytes = status.freeBytes,
            networkAllowed = status.networkAllowed,
            pendingDelete = models.firstOrNull { it.name == deleting },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUiState())

    fun setContextSize(name: String, tokens: Int) = runtime.engine.setContextSize(name, tokens)

    fun refresh() {
        system.value = SystemStatus.read(getApplication())
    }

    fun select(name: String) {
        runtime.models.setActive(name)
        // The next question loads the new model; the old one should not keep its memory until then.
        runtime.engine.releaseNow()
    }

    fun requestDelete(name: String) {
        pendingDelete.value = name
    }

    fun cancelDelete() {
        pendingDelete.value = null
    }

    fun confirmDelete() {
        val name = pendingDelete.value ?: return
        pendingDelete.value = null
        runtime.engine.releaseNow()
        runtime.models.delete(name)
        refresh()
    }

    fun download(file: RemoteModelFile) {
        if (!Storage.fits(file.sizeBytes, system.value.freeBytes)) {
            events.trySend(ModelsEvent.NotEnoughSpace(file.sizeBytes))
            return
        }
        ModelDownloadService.start(getApplication(), file)
    }

    fun cancelTransfer(fileName: String) {
        ModelDownloadService.cancel(getApplication(), fileName)
        Transfers.clear(fileName)
    }

    fun retryTransfer(fileName: String) {
        if (!ModelDownloadService.retry(getApplication(), fileName)) Transfers.clear(fileName)
    }

    /** Runs on the app's scope: a multi-GB copy must survive leaving the screen. */
    fun import(uri: Uri) {
        runtime.scope.launch {
            runtime.importer.import(uri).onFailure { error ->
                if (error is IllegalArgumentException) events.trySend(ModelsEvent.Unsupported(error.message.orEmpty()))
            }
            refresh()
        }
    }

    fun setQuery(query: String) = search.update { it.copy(query = query) }

    fun search() {
        val query = search.value.query.trim()
        if (query.isEmpty()) return
        search.update { it.copy(loading = true, error = null, expanded = null, files = null) }
        viewModelScope.launch {
            runCatching { HuggingFaceClient.search(query) }
                .onSuccess { repos -> search.update { it.copy(loading = false, repos = repos) } }
                .onFailure { error -> search.update { it.copy(loading = false, error = error.message ?: error.javaClass.simpleName) } }
        }
    }

    fun toggleRepo(repo: String) {
        if (search.value.expanded == repo) {
            search.update { it.copy(expanded = null, files = null) }
            return
        }
        search.update { it.copy(expanded = repo, files = null) }
        viewModelScope.launch {
            val files = runCatching { HuggingFaceClient.files(repo) }
                .onFailure { error -> search.update { it.copy(error = error.message) } }
                .getOrDefault(emptyList())
            search.update { if (it.expanded == repo) it.copy(files = files) else it }
        }
    }
}
