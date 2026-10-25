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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localassistant.R
import dev.localassistant.llm.EngineHolder
import dev.localassistant.models.ModelFormat
import dev.localassistant.models.RemoteModelFile
import dev.localassistant.models.TransferState
import dev.localassistant.ui.components.CardNote
import dev.localassistant.ui.components.ConfirmDialog
import dev.localassistant.ui.components.EmptyState
import dev.localassistant.ui.components.GroupCard
import dev.localassistant.ui.components.MetaText
import dev.localassistant.ui.components.ScreenHeader
import dev.localassistant.ui.components.animatedProgress
import dev.localassistant.ui.components.displayModelName
import dev.localassistant.ui.components.formatSize
import dev.localassistant.ui.theme.AssistantIcons
import dev.localassistant.ui.theme.LargeTouch
import dev.localassistant.ui.theme.Spacing

interface ModelsActions {
    fun select(name: String)
    fun setContextSize(name: String, tokens: Int)
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

/**
 * The brains the assistant can answer with. What is already on the phone comes first and in one
 * card, because that is the only group the user can switch between right now; everything below is
 * a download away.
 */
@Composable
fun ModelsScreen(state: ModelsUiState, actions: ModelsActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item {
            ScreenHeader(
                stringResource(R.string.tab_models),
                subtitle = state.freeBytes.takeIf { it != Long.MAX_VALUE }
                    ?.let { stringResource(R.string.models_free_space, formatSize(it)) },
            )
        }
        // Without the network nothing below the installed card can do anything, so it says so first.
        if (!state.networkAllowed) item { NetworkCard(actions::openNetworkSettings) }
        if (state.transfers.isNotEmpty()) item { TransfersCard(state.transfers, actions) }
        item { InstalledCard(state.installed, actions) }
        item { CatalogCard(state.catalog, state.networkAllowed, actions) }
        item { SearchCard(state.search, state.installed.map { it.name }.toSet(), state.networkAllowed, actions) }
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

/** The models on the phone. Importing a file sits here too: it is the other way one arrives. */
@Composable
private fun InstalledCard(models: List<InstalledModelUi>, actions: ModelsActions) {
    GroupCard(stringResource(R.string.models_installed), icon = AssistantIcons.Memory) {
        if (models.isEmpty()) {
            EmptyState(
                icon = AssistantIcons.Storage,
                title = stringResource(R.string.models_empty_title),
                body = stringResource(R.string.models_empty_body),
                actionLabel = stringResource(R.string.models_import),
                onAction = actions::importFile,
            )
        } else {
            models.forEach { model -> ModelRow(model, actions) }
            OutlinedButton(
                onClick = actions::importFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.l, end = Spacing.l, top = Spacing.s)
                    .height(LargeTouch),
            ) {
                Icon(AssistantIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.models_import), modifier = Modifier.padding(start = Spacing.s))
            }
        }
    }
}

/**
 * One installed model. The active one is tinted and labelled: with several models of very
 * different speed installed, which one actually answers is the whole point of the screen.
 */
@Composable
private fun ModelRow(model: InstalledModelUi, actions: ModelsActions) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (model.active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xxs),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(role = Role.RadioButton) { actions.select(model.name) },
            leadingContent = { RadioButton(selected = model.active, onClick = null) },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(
                        displayModelName(model.name),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (model.active) {
                        Text(
                            stringResource(R.string.models_active),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    // The display name drops the quantization, which is the only thing telling two
                    // builds of the same model apart, so the file name stays on the row.
                    MetaText(model.name)
                    MetaText(
                        listOfNotNull(
                            formatSize(model.sizeBytes),
                            stringResource(formatName(model.format)),
                            model.tokensPerSecond?.let { stringResource(R.string.models_speed, it) }
                                ?: stringResource(R.string.models_speed_unknown),
                            model.runningOn?.let { stringResource(R.string.models_running_on, it) },
                        ).joinToString(SEPARATOR),
                    )
                    if (model.format == ModelFormat.GGUF) ContextChoice(model, actions)
                }
            },
            trailingContent = {
                IconButton(onClick = { actions.requestDelete(model.name) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.models_delete))
                }
            },
        )
    }
}

/**
 * How much conversation this model is given to hold. A model whose weights plus conversation go
 * over what Android lets one app hold is not stopped but slowed to a crawl, and which models
 * that hits cannot be told from their size, so the choice is offered rather than guessed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextChoice(model: InstalledModelUi, actions: ModelsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(top = Spacing.xs)) {
        MetaText(stringResource(R.string.models_context))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            EngineHolder.CONTEXT_CHOICES.forEach { tokens ->
                FilterChip(
                    selected = model.contextTokens == tokens,
                    onClick = { actions.setContextSize(model.name, tokens) },
                    label = { Text(stringResource(R.string.models_context_tokens, tokens)) },
                )
            }
        }
    }
}

@Composable
private fun TransfersCard(transfers: List<TransferState>, actions: ModelsActions) {
    GroupCard(stringResource(R.string.models_transfers), icon = AssistantIcons.Download) {
        transfers.forEach { transfer -> TransferRow(transfer, actions) }
    }
}

@Composable
private fun TransferRow(transfer: TransferState, actions: ModelsActions) {
    val dismissLabel = stringResource(if (transfer is TransferState.Failed) R.string.models_dismiss else R.string.action_cancel)
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(displayModelName(transfer.fileName), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            when (transfer) {
                is TransferState.Running -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    LinearProgressIndicator(animatedProgress(transfer.fraction), modifier = Modifier.fillMaxWidth())
                    MetaText(
                        stringResource(
                            R.string.models_progress,
                            formatSize(transfer.doneBytes),
                            formatSize(transfer.totalBytes),
                        ),
                    )
                }
                is TransferState.Failed -> Text(
                    stringResource(R.string.models_transfer_failed, transfer.message),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (transfer is TransferState.Failed) {
                    TextButton(onClick = { actions.retryTransfer(transfer.fileName) }) {
                        Text(stringResource(R.string.models_retry))
                    }
                }
                IconButton(onClick = { actions.cancelTransfer(transfer.fileName) }) {
                    Icon(Icons.Filled.Close, contentDescription = dismissLabel)
                }
            }
        },
    )
}

@Composable
private fun CatalogCard(items: List<CatalogItemUi>, networkAllowed: Boolean, actions: ModelsActions) {
    GroupCard(stringResource(R.string.models_recommended), icon = AssistantIcons.Sparkle) {
        items.forEach { item -> CatalogRow(item, networkAllowed, actions) }
    }
}

/** What the model is, what it costs in space, and one button — in that order, before the tap. */
@Composable
private fun CatalogRow(item: CatalogItemUi, networkAllowed: Boolean, actions: ModelsActions) {
    val file = item.entry.file
    val transfer = item.transfer
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(item.entry.title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(item.entry.noteRes))
                MetaText(
                    listOfNotNull(
                        formatSize(file.sizeBytes),
                        ModelFormat.of(file.fileName)?.let { stringResource(formatName(it)) },
                    ).joinToString(SEPARATOR),
                )
                when {
                    transfer is TransferState.Running ->
                        LinearProgressIndicator(animatedProgress(transfer.fraction), modifier = Modifier.fillMaxWidth())
                    !item.installed && !item.fits -> Text(
                        stringResource(R.string.models_no_space),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
            }
        },
        trailingContent = {
            when {
                item.installed -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.models_installed_badge),
                    tint = MaterialTheme.colorScheme.primary,
                )
                // The bar below already says it is coming; the transfers card holds the cancel.
                transfer is TransferState.Running -> Unit
                else -> FilledTonalButton(
                    onClick = { actions.download(file) },
                    enabled = networkAllowed && item.fits,
                    modifier = Modifier.height(LargeTouch),
                ) {
                    Icon(AssistantIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.models_download), modifier = Modifier.padding(start = Spacing.s))
                }
            }
        },
    )
}

@Composable
private fun SearchCard(search: SearchUi, installedNames: Set<String>, enabled: Boolean, actions: ModelsActions) {
    GroupCard(stringResource(R.string.models_search_title), icon = Icons.Filled.Search) {
        OutlinedTextField(
            value = search.query,
            onValueChange = actions::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
            enabled = enabled,
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.models_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.search() }),
        )
        if (search.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s))
        }
        search.error?.let { message ->
            Text(
                stringResource(R.string.models_search_failed, message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
        }
        search.repos.forEach { repo ->
            val expanded = search.expanded == repo.id
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { actions.toggleRepo(repo.id) },
                headlineContent = { Text(repo.id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                    )
                },
            )
            if (expanded) RepoFiles(search.files, installedNames, actions)
        }
        CardNote(stringResource(R.string.models_network_note))
    }
}

@Composable
private fun RepoFiles(files: List<RemoteModelFile>?, installedNames: Set<String>, actions: ModelsActions) {
    when {
        files == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.s))
        files.isEmpty() -> CardNote(stringResource(R.string.models_no_files))
        else -> files.forEach { file ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(start = Spacing.l),
                headlineContent = { Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { MetaText(formatSize(file.sizeBytes)) },
                trailingContent = {
                    if (file.fileName in installedNames) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.models_installed_badge),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        IconButton(onClick = { actions.download(file) }) {
                            Icon(AssistantIcons.Download, contentDescription = stringResource(R.string.models_download))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun NetworkCard(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(stringResource(R.string.models_network_off), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(LargeTouch)) {
                Text(stringResource(R.string.models_network_open_settings))
            }
        }
    }
}

private fun formatName(format: ModelFormat): Int = when (format) {
    ModelFormat.GGUF -> R.string.models_format_gguf
    ModelFormat.LITERT_LM -> R.string.models_format_litert
}

private const val SEPARATOR = " · "
