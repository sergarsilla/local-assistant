package dev.localassistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.localassistant.AssistantRuntime
import dev.localassistant.R
import dev.localassistant.models.Catalog
import dev.localassistant.models.HuggingFaceClient
import dev.localassistant.models.InstalledModel
import dev.localassistant.models.ModelDownloadService
import dev.localassistant.models.ModelFormat
import dev.localassistant.models.RemoteModelFile
import dev.localassistant.models.RemoteRepo
import dev.localassistant.models.TransferState
import dev.localassistant.models.Transfers
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = AssistantRuntime.models
    val installed by store.installed.collectAsState()
    val activeName by store.activeName.collectAsState()
    val transfers by Transfers.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<InstalledModel?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importError = null
            // A multi-GB copy must survive leaving this tab, so it runs on the app's scope.
            AssistantRuntime.scope.launch {
                AssistantRuntime.importer.import(uri).onFailure { e ->
                    if (e is IllegalArgumentException) importError = e.message
                }
            }
        }
    }
    val download = { file: RemoteModelFile -> ModelDownloadService.start(context, file) }
    val networkAllowed = rememberNetworkPermission()
    val effectiveActive = activeName ?: installed.firstOrNull()?.name

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        section(R.string.models_installed)
        if (installed.isEmpty()) {
            item { Text(stringResource(R.string.models_none_installed)) }
        }
        items(installed, key = { it.name }) { model ->
            InstalledRow(
                model = model,
                active = model.name == effectiveActive,
                onSelect = {
                    store.setActive(model.name)
                    AssistantRuntime.engine.releaseNow()
                },
                onDelete = { pendingDelete = model },
            )
        }
        if (installed.any { it.sizeBytes > PANEL_COMFORTABLE_BYTES }) {
            item {
                Text(
                    stringResource(R.string.models_panel_ram_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.models_import)) }
        }
        importError?.let { name ->
            item { Text(stringResource(R.string.models_import_unsupported, name), color = MaterialTheme.colorScheme.error) }
        }

        if (transfers.isNotEmpty()) {
            section(R.string.models_transfers)
            items(transfers.values.toList(), key = { "t:" + it.fileName }) { transfer ->
                TransferRow(transfer, onCancel = {
                    ModelDownloadService.cancel(context, transfer.fileName)
                    Transfers.clear(transfer.fileName)
                })
            }
        }

        if (!networkAllowed) {
            item { NetworkBanner() }
        }

        section(R.string.models_recommended)
        items(Catalog.recommended, key = { "c:" + it.file.fileName }) { entry ->
            val isInstalled = installed.any { it.name == entry.file.fileName }
            RemoteRow(
                title = entry.title,
                subtitle = stringResource(entry.noteRes),
                file = entry.file,
                installed = isInstalled,
                busy = entry.file.fileName in transfers || !networkAllowed,
                onDownload = { download(entry.file) },
            )
        }

        section(R.string.models_search_title)
        item {
            SearchSection(
                enabled = networkAllowed,
                installedNames = installed.map { it.name }.toSet(),
                busyNames = transfers.keys,
                onDownload = download,
            )
        }
        item {
            Text(
                stringResource(R.string.models_network_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = { Text(stringResource(R.string.models_delete_confirm, model.name, formatSize(model.sizeBytes))) },
            confirmButton = {
                TextButton(onClick = {
                    AssistantRuntime.engine.releaseNow()
                    store.delete(model.name)
                    pendingDelete = null
                }) { Text(stringResource(R.string.models_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

// The system caps the assistant panel's process near 3 GiB (measured on GrapheneOS, Android 17).
private const val PANEL_COMFORTABLE_BYTES = 2_500_000_000L

private fun LazyListScope.section(title: Int) {
    item(key = "s:$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun InstalledRow(model: InstalledModel, active: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = active, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.bodyLarge)
                val speed = model.tokensPerSecond?.let { stringResource(R.string.models_speed, it) }
                    ?: stringResource(R.string.models_speed_unknown)
                Text(
                    "${formatSize(model.sizeBytes)} · ${formatName(model.format)} · $speed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.models_delete)) }
        }
    }
}

@Composable
private fun TransferRow(transfer: TransferState, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(transfer.fileName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) {
                Text(stringResource(if (transfer is TransferState.Failed) R.string.models_dismiss else R.string.action_cancel))
            }
        }
        when (transfer) {
            is TransferState.Running -> {
                LinearProgressIndicator(progress = { transfer.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${formatSize(transfer.doneBytes)} / ${formatSize(transfer.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is TransferState.Failed -> Text(
                stringResource(R.string.models_transfer_failed, transfer.message),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RemoteRow(
    title: String,
    subtitle: String?,
    file: RemoteModelFile,
    installed: Boolean,
    busy: Boolean,
    onDownload: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "${formatSize(file.sizeBytes)} · ${ModelFormat.of(file.fileName)?.let { formatName(it) }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            installed -> Text(stringResource(R.string.models_installed_badge), style = MaterialTheme.typography.labelLarge)
            else -> FilledTonalButton(onClick = onDownload, enabled = !busy) {
                Text(stringResource(R.string.models_download))
            }
        }
    }
}

@Composable
private fun SearchSection(
    enabled: Boolean,
    installedNames: Set<String>,
    busyNames: Set<String>,
    onDownload: (RemoteModelFile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<RemoteRepo>>(emptyList()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<RemoteModelFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val search = {
        scope.launch {
            error = null
            expanded = null
            runCatching { HuggingFaceClient.search(query) }
                .onSuccess { repos = it }
                .onFailure { error = it.message }
        }
        Unit
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.models_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            FilledTonalButton(onClick = search, enabled = enabled && query.isNotBlank()) {
                Text(stringResource(R.string.models_search))
            }
        }
        error?.let { Text(stringResource(R.string.models_search_failed, it), color = MaterialTheme.colorScheme.error) }
        repos.forEach { repo ->
            Column {
                Text(
                    repo.id,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (expanded == repo.id) {
                            expanded = null
                        } else {
                            expanded = repo.id
                            files = null
                            scope.launch {
                                files = runCatching { HuggingFaceClient.files(repo.id) }
                                    .onFailure { error = it.message }
                                    .getOrDefault(emptyList())
                            }
                        }
                    }.padding(vertical = 8.dp),
                )
                if (expanded == repo.id) {
                    val list = files
                    when {
                        list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        list.isEmpty() -> Text(stringResource(R.string.models_no_files), style = MaterialTheme.typography.bodySmall)
                        else -> list.forEach { file ->
                            RemoteRow(
                                title = file.fileName,
                                subtitle = null,
                                file = file,
                                installed = file.fileName in installedNames,
                                busy = file.fileName in busyNames,
                                onDownload = { onDownload(file) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** GrapheneOS can revoke network access per app; re-checked whenever the screen resumes. */
@Composable
private fun rememberNetworkPermission(): Boolean {
    val context = LocalContext.current
    fun granted() = context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    LifecycleResumeEffect(Unit) {
        allowed = granted()
        onPauseOrDispose {}
    }
    return allowed
}

@Composable
private fun NetworkBanner() {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.models_network_off))
            FilledTonalButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text(stringResource(R.string.models_network_open_settings)) }
        }
    }
}

@Composable
private fun formatSize(bytes: Long): String =
    if (bytes <= 0) "?" else Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
private fun formatName(format: ModelFormat): String = stringResource(
    when (format) {
        ModelFormat.GGUF -> R.string.models_format_gguf
        ModelFormat.LITERT_LM -> R.string.models_format_litert
    },
)
