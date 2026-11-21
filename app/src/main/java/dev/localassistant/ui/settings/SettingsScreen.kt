package dev.localassistant.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.localassistant.BuildConfig
import dev.localassistant.R
import dev.localassistant.llm.Accelerator
import dev.localassistant.models.TransferState
import dev.localassistant.tools.MessagingApp
import dev.localassistant.ui.components.ActionRow
import dev.localassistant.ui.components.CardNote
import dev.localassistant.ui.components.CardSubheader
import dev.localassistant.ui.components.ConfirmDialog
import dev.localassistant.ui.components.GroupCard
import dev.localassistant.ui.components.MetaText
import dev.localassistant.ui.components.ScreenHeader
import dev.localassistant.ui.components.SwitchRow
import dev.localassistant.ui.components.animatedProgress
import dev.localassistant.ui.components.formatSize
import dev.localassistant.ui.theme.AssistantIcons
import dev.localassistant.ui.theme.Spacing
import dev.localassistant.voice.VoicePack
import dev.localassistant.wake.WakePhrase

interface SettingsActions {
    fun openAssistantSettings()
    fun openAssistSettings()
    fun openKeyboardSettings()
    fun requestMicrophone()
    fun requestContacts()
    fun requestNotifications()
    fun openAppSettings()
    fun openAccessibilitySettings()
    fun setMapsApp(packageName: String?)
    fun setMessagingApp(app: MessagingApp)
    fun setListenOnOpen(value: Boolean)
    fun setSpeakReplies(value: Boolean)
    fun setKeyboardListens(value: Boolean)
    fun downloadPack(pack: VoicePack)
    fun cancelPackDownload(pack: VoicePack)
    fun usePack(pack: VoicePack)
    fun requestPackDelete(pack: VoicePack)
    fun confirmPackDelete()
    fun cancelPackDelete()
    fun setAccelerator(accelerator: Accelerator)
    fun setUnderstandImages(value: Boolean)
    fun setWebSearch(value: Boolean)
    fun setSaveHistory(value: Boolean)
    fun setWakeWord(value: Boolean)
    fun setWakePhrase(phrase: WakePhrase)
    fun setAppLanguage(tag: String)
}

/**
 * One card per subject, in the order someone sets the assistant up: who answers, what it hears,
 * what it says, how it thinks, what it may touch. A flat list of every switch looks the same from
 * top to bottom and gives no clue where to stop reading.
 */
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item {
            ScreenHeader(stringResource(R.string.tab_settings), subtitle = stringResource(R.string.settings_subtitle))
        }
        item { AssistantCard(state, actions) }
        item { VoiceCard(state, actions) }
        item { DictationCard(state, actions) }
        item { WakeWordCard(state, actions) }
        item { AppsCard(state.apps, actions) }
        item { ModelCard(state, actions) }
        item { PrivacyCard(state, actions) }
        item { PermissionsCard(state, actions) }
        item { LanguageCard(state, actions) }
        item { AboutCard() }
    }

    state.pendingPackDelete?.let { pack ->
        ConfirmDialog(
            title = stringResource(R.string.settings_pack_delete_title),
            text = stringResource(
                if (pack.isKokoro) R.string.settings_pack_delete_kokoro else R.string.settings_pack_delete_body,
                packLabel(pack),
            ),
            confirmLabel = stringResource(R.string.models_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = actions::confirmPackDelete,
            onDismiss = actions::cancelPackDelete,
            destructive = true,
        )
    }
}

@Composable
private fun AssistantCard(state: SettingsUiState, actions: SettingsActions) {
    GroupCard(stringResource(R.string.settings_assistant), icon = AssistantIcons.Sparkle) {
        ActionRow(
            title = stringResource(R.string.settings_default_assistant),
            summary = stringResource(if (state.status.isAssistant) R.string.setup_status_active else R.string.setup_status_inactive),
            icon = AssistantIcons.Sparkle,
            onClick = actions::openAssistantSettings,
        )
        SwitchRow(
            title = stringResource(R.string.voice_listen_on_open),
            checked = state.voice?.listenOnOpen == true,
            onCheckedChange = actions::setListenOnOpen,
            icon = AssistantIcons.Mic,
        )
        ActionRow(
            title = stringResource(R.string.settings_screen),
            summary = stringResource(
                R.string.settings_screen_summary,
                onOff(state.status.screenTextEnabled),
                onOff(state.status.screenshotEnabled),
            ),
            icon = AssistantIcons.Smartphone,
            onClick = actions::openAssistSettings,
        )
    }
}

/** Microphone, spoken replies, and the three lists of packs that make either of them work. */
@Composable
private fun VoiceCard(state: SettingsUiState, actions: SettingsActions) {
    GroupCard(stringResource(R.string.voice_title), icon = AssistantIcons.Mic) {
        SwitchRow(
            title = stringResource(R.string.voice_speak_replies),
            checked = state.voice?.speakReplies == true,
            onCheckedChange = actions::setSpeakReplies,
            icon = AssistantIcons.VolumeUp,
        )
        CardSubheader(stringResource(R.string.settings_recognition))
        state.speechPacks.forEach { pack -> PackRow(pack, actions) }
        CardSubheader(stringResource(R.string.voice_voices_es))
        state.voicesEs.forEach { pack -> PackRow(pack, actions) }
        CardSubheader(stringResource(R.string.voice_voices_en))
        state.voicesEn.forEach { pack -> PackRow(pack, actions) }
    }
}

@Composable
private fun DictationCard(state: SettingsUiState, actions: SettingsActions) {
    GroupCard(stringResource(R.string.settings_dictation), icon = AssistantIcons.Keyboard) {
        ActionRow(
            title = stringResource(R.string.keyboard_name),
            summary = stringResource(if (state.status.keyboardEnabled) R.string.settings_keyboard_on else R.string.settings_keyboard_off),
            icon = AssistantIcons.Keyboard,
            onClick = actions::openKeyboardSettings,
        )
        SwitchRow(
            title = stringResource(R.string.settings_keyboard_listens),
            checked = state.voice?.keyboardListensOnOpen == true,
            onCheckedChange = actions::setKeyboardListens,
        )
        CardNote(stringResource(R.string.settings_dictation_note))
    }
}

@Composable
private fun WakeWordCard(state: SettingsUiState, actions: SettingsActions) {
    val available = state.status.isAssistant && state.status.micGranted
    GroupCard(stringResource(R.string.settings_wake), icon = AssistantIcons.GraphicEq) {
        SwitchRow(
            title = stringResource(R.string.settings_wake_title),
            summary = stringResource(if (available) R.string.settings_wake_summary else R.string.settings_wake_unavailable),
            checked = state.wakeWord.enabled && available,
            onCheckedChange = actions::setWakeWord,
            icon = AssistantIcons.GraphicEq,
            // A setting left on must still be switchable off when it cannot run.
            enabled = available || state.wakeWord.enabled,
        )
        if (state.wakeWord.enabled && available) {
            WakePhrase.entries.forEach { phrase ->
                RadioRow(
                    label = "“${phrase.label}”",
                    selected = state.wakeWord.phrase == phrase,
                    onClick = { actions.setWakePhrase(phrase) },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(state: SettingsUiState, actions: SettingsActions) {
    GroupCard(stringResource(R.string.settings_model), icon = AssistantIcons.Memory) {
        CardSubheader(stringResource(R.string.settings_accel))
        Accelerator.entries.forEach { accelerator ->
            val broken = when (accelerator) {
                Accelerator.GPU -> state.engine.gpuBroken
                Accelerator.NPU -> state.engine.npuBroken
                Accelerator.CPU -> false
            }
            RadioRow(
                label = stringResource(accelerator.label()),
                summary = stringResource(if (broken) accelerator.brokenNote() else accelerator.note()),
                selected = state.engine.accelerator == accelerator,
                warning = broken && state.engine.accelerator == accelerator,
                onClick = { actions.setAccelerator(accelerator) },
            )
        }
        SwitchRow(
            title = stringResource(R.string.settings_images),
            summary = stringResource(R.string.settings_images_summary),
            checked = state.engine.understandImages,
            onCheckedChange = actions::setUnderstandImages,
            icon = AssistantIcons.Image,
        )
    }
}

@Composable
private fun PrivacyCard(state: SettingsUiState, actions: SettingsActions) {
    GroupCard(stringResource(R.string.settings_privacy), icon = Icons.Filled.Lock) {
        SwitchRow(
            title = stringResource(R.string.brain_web_search),
            summary = stringResource(R.string.brain_web_search_note),
            checked = state.webSearch,
            onCheckedChange = actions::setWebSearch,
            icon = AssistantIcons.Public,
        )
        SwitchRow(
            title = stringResource(R.string.settings_save_history),
            summary = stringResource(R.string.settings_save_history_summary),
            checked = state.saveHistory,
            onCheckedChange = actions::setSaveHistory,
            icon = AssistantIcons.History,
        )
    }
}

/** Where "take me to" and "text" end up, so neither is decided by whichever app claims the link first. */
@Composable
private fun AppsCard(apps: AppsUi, actions: SettingsActions) {
    GroupCard(stringResource(R.string.settings_apps), icon = Icons.Filled.Place) {
        CardSubheader(stringResource(R.string.settings_apps_maps))
        RadioRow(
            label = stringResource(R.string.settings_apps_system),
            selected = apps.mapsPackage == null,
            onClick = { actions.setMapsApp(null) },
            summary = stringResource(R.string.settings_apps_system_note),
        )
        apps.mapsChoices.forEach { choice ->
            RadioRow(
                label = choice.label,
                selected = apps.mapsPackage == choice.packageName,
                onClick = { actions.setMapsApp(choice.packageName) },
            )
        }
        CardSubheader(stringResource(R.string.settings_apps_messages))
        RadioRow(
            label = stringResource(R.string.settings_apps_sms),
            selected = apps.messaging == MessagingApp.SMS || !apps.whatsAppInstalled,
            onClick = { actions.setMessagingApp(MessagingApp.SMS) },
        )
        if (apps.whatsAppInstalled) {
            RadioRow(
                label = stringResource(R.string.settings_apps_whatsapp),
                selected = apps.messaging == MessagingApp.WHATSAPP,
                onClick = { actions.setMessagingApp(MessagingApp.WHATSAPP) },
                summary = stringResource(R.string.settings_apps_whatsapp_note),
            )
        }
    }
}

/** Everything Android lets this app do, in one place, with what each permission is for. */
@Composable
private fun PermissionsCard(state: SettingsUiState, actions: SettingsActions) {
    val status = state.status
    GroupCard(stringResource(R.string.settings_permissions), icon = Icons.Filled.Lock) {
        PermissionRow(
            title = stringResource(R.string.settings_microphone),
            summary = stringResource(if (status.micGranted) R.string.voice_mic_granted else R.string.settings_microphone_off),
            icon = AssistantIcons.Mic,
            granted = status.micGranted,
            request = actions::requestMicrophone,
        )
        PermissionRow(
            title = stringResource(R.string.settings_contacts),
            summary = stringResource(if (status.contactsGranted) R.string.settings_contacts_on else R.string.settings_contacts_off),
            icon = Icons.Filled.Person,
            granted = status.contactsGranted,
            request = actions::requestContacts,
        )
        PermissionRow(
            title = stringResource(R.string.settings_notifications),
            summary = stringResource(if (status.notificationsGranted) R.string.settings_notifications_on else R.string.settings_notifications_off),
            icon = Icons.Filled.Notifications,
            granted = status.notificationsGranted,
            request = actions::requestNotifications,
        )
        // Accessibility is switched on only from the system's own list, never by an app.
        ActionRow(
            title = stringResource(R.string.settings_read_apps),
            summary = stringResource(if (status.appReaderEnabled) R.string.settings_read_apps_on else R.string.settings_read_apps_off),
            icon = AssistantIcons.Visibility,
            actionLabel = if (status.appReaderEnabled) null else stringResource(R.string.step_allow),
            onClick = actions::openAccessibilitySettings,
        )
        // Android only lets the user change this one from the system's own page for the app.
        ActionRow(
            title = stringResource(R.string.settings_network),
            summary = stringResource(if (status.networkAllowed) R.string.settings_network_on else R.string.settings_network_off),
            icon = AssistantIcons.Public,
            onClick = actions::openAppSettings,
        )
        ActionRow(
            title = stringResource(R.string.settings_permissions_manage),
            summary = stringResource(R.string.settings_permissions_manage_note),
            onClick = actions::openAppSettings,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    summary: String,
    icon: ImageVector,
    granted: Boolean,
    request: () -> Unit,
) {
    ActionRow(
        title = title,
        summary = summary,
        icon = icon,
        actionLabel = if (granted) null else stringResource(R.string.step_allow),
        onClick = if (granted) null else request,
    )
}

@Composable
private fun LanguageCard(state: SettingsUiState, actions: SettingsActions) {
    GroupCard(stringResource(R.string.setup_language), icon = AssistantIcons.Translate) {
        LANGUAGE_TAGS.forEach { tag ->
            RadioRow(
                // Each language is named in itself; translating these would hide the one being looked for.
                label = when (tag) {
                    "es" -> "Español"
                    "en" -> "English"
                    else -> stringResource(R.string.setup_language_system)
                },
                selected = state.appLanguage == tag,
                onClick = { actions.setAppLanguage(tag) },
            )
        }
    }
}

@Composable
private fun AboutCard() {
    GroupCard(stringResource(R.string.settings_about), icon = Icons.Filled.Info) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Filled.Lock, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.settings_about_privacy)) },
            supportingContent = { Text(stringResource(R.string.settings_about_body)) },
        )
        CardNote(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
    }
}

/** A voice or recognition pack: pick it, fetch it, or delete it, in one row. */
@Composable
private fun PackRow(item: PackUi, actions: SettingsActions) {
    val transfer = item.transfer
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = item.installed && !item.inUse, role = Role.RadioButton) { actions.usePack(item.pack) },
        leadingContent = { RadioButton(selected = item.inUse, onClick = null, enabled = item.installed) },
        headlineContent = { Text(packLabel(item.pack)) },
        supportingContent = {
            when (transfer) {
                is TransferState.Running -> LinearProgressIndicator(
                    animatedProgress(transfer.fraction),
                    modifier = Modifier.fillMaxWidth(),
                )
                is TransferState.Failed -> Text(
                    stringResource(R.string.models_transfer_failed, transfer.message),
                    color = MaterialTheme.colorScheme.error,
                )
                null -> MetaText(formatSize(item.pack.approxBytes))
            }
        },
        trailingContent = {
            when {
                transfer is TransferState.Running -> IconButton(onClick = { actions.cancelPackDownload(item.pack) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                }
                !item.installed -> IconButton(onClick = { actions.downloadPack(item.pack) }) {
                    Icon(AssistantIcons.Download, contentDescription = stringResource(R.string.models_download))
                }
                else -> IconButton(onClick = { actions.requestPackDelete(item.pack) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.models_delete))
                }
            }
        },
    )
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    summary: String? = null,
    warning: Boolean = false,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.RadioButton, onClick = onClick),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(label) },
        supportingContent = summary?.let {
            {
                Text(
                    it,
                    color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = if (warning) {
            { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
    )
}

@Composable
private fun onOff(value: Boolean) = stringResource(if (value) R.string.settings_on else R.string.settings_off)

private fun Accelerator.label() = when (this) {
    Accelerator.GPU -> R.string.settings_accel_gpu
    Accelerator.CPU -> R.string.settings_accel_cpu
    Accelerator.NPU -> R.string.settings_accel_npu
}

private fun Accelerator.note() = when (this) {
    Accelerator.GPU -> R.string.settings_accel_gpu_note
    Accelerator.CPU -> R.string.settings_accel_cpu_note
    Accelerator.NPU -> R.string.settings_accel_npu_note
}

private fun Accelerator.brokenNote() = when (this) {
    Accelerator.NPU -> R.string.settings_accel_npu_broken
    else -> R.string.brain_gpu_broken
}

@Composable
internal fun packLabel(pack: VoicePack): String = stringResource(
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

/** "" follows the system; the app ships Spanish and English. */
private val LANGUAGE_TAGS = listOf("", "es", "en")
