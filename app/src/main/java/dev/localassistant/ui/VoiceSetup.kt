package dev.localassistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.localassistant.AssistantRuntime
import dev.localassistant.R
import dev.localassistant.models.ModelDownloadService
import dev.localassistant.models.TransferState
import dev.localassistant.models.Transfers
import dev.localassistant.voice.PackKind
import dev.localassistant.voice.VoicePack
import dev.localassistant.voice.VoicePacks

@Composable
fun VoiceSetup() {
    val context = LocalContext.current
    val settings = AssistantRuntime.voiceSettings
    val prefs by settings.state.collectAsState()
    val installed by VoicePacks.installed.collectAsState()
    val transfers by Transfers.state.collectAsState()
    var micGranted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }

    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.voice_title), style = MaterialTheme.typography.titleMedium)
        if (micGranted) {
            Text(stringResource(R.string.voice_mic_granted), style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text(stringResource(R.string.voice_mic_permission))
            }
        }

        SwitchRow(
            label = stringResource(R.string.voice_listen_on_open),
            checked = prefs.listenOnOpen,
            onChange = { value -> settings.update { it.copy(listenOnOpen = value) } },
        )
        SwitchRow(
            label = stringResource(R.string.voice_speak_replies),
            checked = prefs.speakReplies,
            onChange = { value -> settings.update { it.copy(speakReplies = value) } },
        )

        Text(stringResource(R.string.voice_packs), style = MaterialTheme.typography.titleSmall)
        VoicePack.entries.filter { it.kind == PackKind.SPEECH_TO_TEXT }.forEach { pack ->
            PackRow(
                pack = pack,
                installed = pack in installed,
                inUse = prefs.speechToText == pack,
                transfer = transfers[pack.id],
                onDownload = { ModelDownloadService.startPack(context, pack) },
                onUse = { settings.update { it.copy(speechToText = pack) } },
                onDelete = { VoicePacks.delete(pack) },
            )
        }

        listOf("es", "en").forEach { language ->
            Text(
                stringResource(if (language == "es") R.string.voice_voices_es else R.string.voice_voices_en),
                style = MaterialTheme.typography.titleSmall,
            )
            VoicePack.voicesFor(language).forEach { pack ->
                PackRow(
                    pack = pack,
                    installed = pack in installed,
                    inUse = prefs.voiceFor(language) == pack,
                    transfer = transfers[pack.id],
                    onDownload = { ModelDownloadService.startPack(context, pack) },
                    onUse = { settings.update { it.copy(voices = it.voices + (language to pack)) } },
                    onDelete = { VoicePacks.delete(pack) },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PackRow(
    pack: VoicePack,
    installed: Boolean,
    inUse: Boolean,
    transfer: TransferState?,
    onDownload: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val label = stringResource(
        when (pack) {
            VoicePack.STT_CANARY -> R.string.voice_pack_stt_canary
            VoicePack.STT_PARAKEET -> R.string.voice_pack_stt_parakeet
            VoicePack.TTS_ES_DAVEFX -> R.string.voice_pack_es_davefx
            VoicePack.TTS_ES_MIRO -> R.string.voice_pack_es_miro
            VoicePack.TTS_ES_SHARVARD -> R.string.voice_pack_es_sharvard
            VoicePack.TTS_MX_CLAUDE -> R.string.voice_pack_mx_claude
            VoicePack.TTS_AR_DANIELA -> R.string.voice_pack_ar_daniela
            VoicePack.TTS_KOKORO_ES, VoicePack.TTS_KOKORO_EN -> R.string.voice_pack_kokoro
            VoicePack.TTS_EN_LESSAC -> R.string.voice_pack_en_lessac
        },
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = inUse, onClick = onUse, enabled = installed)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    Formatter.formatShortFileSize(context, pack.approxBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                transfer is TransferState.Running -> Unit
                !installed -> FilledTonalButton(onClick = onDownload) { Text(stringResource(R.string.models_download)) }
                inUse -> TextButton(onClick = onDelete) { Text(stringResource(R.string.models_delete)) }
                else -> TextButton(onClick = onUse) { Text(stringResource(R.string.voice_pack_use)) }
            }
        }
        when (transfer) {
            is TransferState.Running -> LinearProgressIndicator(
                progress = { transfer.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            is TransferState.Failed -> Text(
                stringResource(R.string.models_transfer_failed, transfer.message),
                color = MaterialTheme.colorScheme.error,
            )
            null -> Unit
        }
    }
}
