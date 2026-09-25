package dev.localassistant.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localassistant.R
import dev.localassistant.models.ModelFormat
import dev.localassistant.models.RemoteModelFile
import dev.localassistant.models.TransferState
import dev.localassistant.ui.components.ConfirmDialog
import dev.localassistant.ui.components.EmptyState
import dev.localassistant.ui.components.SectionHeader
import dev.localassistant.ui.components.formatSize
import dev.localassistant.ui.theme.AssistantIcons
import dev.localassistant.ui.theme.Spacing

interface ModelsActions {
    fun select(name: String)
    fun requestDelete(name: String)
    fun confirmDelete()
    fun cancelDelete()
    fun download(file: RemoteModelFile)
    fun cancelTransfer(fileName: String)
    fun retryTransfer(fileName: String)
    fun importFile()
    fun setQuery(query: String)
    fun search()
    fun toggleRepo(repo: String)
    fun openNetworkSettings()
}

@Composable
fun ModelsScreen(state: ModelsUiState, actions: ModelsActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item {
            Column(Modifier.padding(vertical = Spacing.l)) {
                Text(stringResource(R.string.tab_models), style = MaterialTheme.typography.headlineMedium)
                if (state.freeBytes != Long.MAX_VALUE) {
                    Text(
                        stringResource(R.string.models_free_space, formatSize(state.freeBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        installedSection(state, actions)
        if (state.transfers.isNotEmpty()) {
            header(R.string.models_transfers)
            items(state.transfers, key = { "t:" + it.fileName }) { TransferRow(it, actions) }
        }
        if (!state.networkAllowed) item { NetworkBanner(actions::openNetworkSettings) }
        header(R.string.models_recommended)
        items(state.catalog, key = { "c:" + it.entry.file.fileName }) { CatalogCard(it, state.networkAllowed, actions) }
        header(R.string.models_search_title)
        item { SearchSection(state.search, state.installed.map { it.name }.toSet(), state.networkAllowed, actions) }
        item {
            Text(
                stringResource(R.string.models_network_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    state.pendingDelete?.let { model ->
        ConfirmDialog(
            title = stringResource(R.string.models_delete_title),
            text = stringResource(R.string.models_delete_confirm, model.name, formatSize(model.sizeBytes)),
            confirmLabel = stringResource(R.string.models_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = actions::confirmDelete,
            onDismiss = actions::cancelDelete,
            destructive = true,
        )
    }
}

private fun LazyListScope.header(title: Int) {
    item(key = "h:$title") { SectionHeader(stringResource(title), Modifier.padding(start = 0.dp)) }
}

private fun LazyListScope.installedSection(state: ModelsUiState, actions: ModelsActions) {
    header(R.string.models_installed)
    if (state.installed.isEmpty()) {
        item {
            EmptyState(
                icon = AssistantIcons.Memory,
                title = stringResource(R.string.models_empty_title),
                body = stringResource(R.string.models_empty_body),
            )
        }
    }
    items(state.installed, key = { "i:" + it.name }) { InstalledCard(it, actions) }
    item {
        OutlinedButton(onClick = actions::importFile) {
            Icon(AssistantIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.models_import), modifier = Modifier.padding(start = Spacing.s))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledCard(model: InstalledModelUi, actions: ModelsActions) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { actions.select(model.name) },
        colors = if (model.active) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(start = Spacing.xs, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = model.active, onClick = { actions.select(model.name) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(model.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    MetaText(formatSize(model.sizeBytes))
                    MetaText(stringResource(formatName(model.format)))
                    MetaText(
                        model.tokensPerSecond?.let { stringResource(R.string.models_speed, it) }
                            ?: stringResource(R.string.models_speed_unknown),
                    )
                    model.runningOn?.let { MetaText(stringResource(R.string.models_running_on, it)) }
                }
            }
            IconButton(onClick = { actions.requestDelete(model.name) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.models_delete))
            }
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TransferRow(transfer: TransferState, actions: ModelsActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                transfer.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (transfer is TransferState.Failed) {
                TextButton(onClick = { actions.retryTransfer(transfer.fileName) }) { Text(stringResource(R.string.models_retry)) }
            }
            TextButton(onClick = { actions.cancelTransfer(transfer.fileName) }) {
                Text(stringResource(if (transfer is TransferState.Failed) R.string.models_dismiss else R.string.action_cancel))
            }
        }
        when (transfer) {
            is TransferState.Running -> {
                LinearProgressIndicator(progress = { transfer.fraction }, modifier = Modifier.fillMaxWidth())
                MetaText("${formatSize(transfer.doneBytes)} / ${formatSize(transfer.totalBytes)}")
            }
            is TransferState.Failed -> Text(
                stringResource(R.string.models_transfer_failed, transfer.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogCard(item: CatalogItemUi, networkAllowed: Boolean, actions: ModelsActions) {
    val file = item.entry.file
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(item.entry.title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(item.entry.noteRes), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                AssistChip(onClick = {}, label = { Text(formatSize(file.sizeBytes)) }, enabled = false)
                ModelFormat.of(file.fileName)?.let { format ->
                    AssistChip(onClick = {}, label = { Text(stringResource(formatName(format))) }, enabled = false)
                }
            }
            val transfer = item.transfer
            when {
                item.installed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.models_installed_badge), style = MaterialTheme.typography.labelLarge)
                }
                transfer is TransferState.Running -> LinearProgressIndicator(progress = { transfer.fraction }, modifier = Modifier.fillMaxWidth())
                !item.fits -> Text(
                    stringResource(R.string.models_no_space),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> FilledTonalButton(onClick = { actions.download(file) }, enabled = networkAllowed) {
                    Icon(AssistantIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.models_download), modifier = Modifier.padding(start = Spacing.s))
                }
            }
        }
    }
}

@Composable
private fun SearchSection(search: SearchUi, installedNames: Set<String>, enabled: Boolean, actions: ModelsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedTextField(
            value = search.query,
            onValueChange = actions::setQuery,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.models_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.search() }),
        )
        if (search.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        search.error?.let {
            Text(stringResource(R.string.models_search_failed, it), color = MaterialTheme.colorScheme.error)
        }
        search.repos.forEach { repo ->
            val expanded = search.expanded == repo.id
            Row(
                Modifier.fillMaxWidth().clickable { actions.toggleRepo(repo.id) }.padding(vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(repo.id, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
            if (expanded) {
                val files = search.files
                when {
                    files == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    files.isEmpty() -> MetaText(stringResource(R.string.models_no_files))
                    else -> files.forEach { file ->
                        Row(Modifier.fillMaxWidth().padding(start = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(file.fileName, style = MaterialTheme.typography.bodyMedium)
                                MetaText(formatSize(file.sizeBytes))
                            }
                            if (file.fileName in installedNames) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.models_installed_badge))
                            } else {
                                IconButton(onClick = { actions.download(file) }) {
                                    Icon(AssistantIcons.Download, contentDescription = stringResource(R.string.models_download))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkBanner(onOpenSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(R.string.models_network_off), color = MaterialTheme.colorScheme.onErrorContainer)
            FilledTonalButton(onClick = onOpenSettings) { Text(stringResource(R.string.models_network_open_settings)) }
        }
    }
}

private fun formatName(format: ModelFormat): Int = when (format) {
    ModelFormat.GGUF -> R.string.models_format_gguf
    ModelFormat.LITERT_LM -> R.string.models_format_litert
}
