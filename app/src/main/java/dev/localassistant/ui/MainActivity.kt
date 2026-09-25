package dev.localassistant.ui

import android.Manifest
import android.app.LocaleManager
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

class MainActivity : ComponentActivity() {
    private var isAssistant by mutableStateOf(false)
    private var tab by mutableIntStateOf(TAB_MODELS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Only used for download progress; the app works without it.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        tab = intent.getIntExtra(EXTRA_TAB, if (savedInstanceState == null) TAB_MODELS else tab)
        setContent {
            AssistantTheme {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == TAB_MODELS,
                                onClick = { tab = TAB_MODELS },
                                icon = { Text("◆") },
                                label = { Text(stringResource(R.string.tab_models)) },
                            )
                            NavigationBarItem(
                                selected = tab == TAB_HISTORY,
                                onClick = { tab = TAB_HISTORY },
                                icon = { Text("☰") },
                                label = { Text(stringResource(R.string.tab_history)) },
                            )
                            NavigationBarItem(
                                selected = tab == TAB_SETUP,
                                onClick = { tab = TAB_SETUP },
                                icon = { Text("⚙") },
                                label = { Text(stringResource(R.string.tab_setup)) },
                            )
                        }
                    },
                ) { padding ->
                    val modifier = Modifier.padding(padding)
                    when (tab) {
                        TAB_SETUP -> SetupScreen(
                            isAssistant = isAssistant,
                            onOpenSettings = { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) },
                            modifier = modifier,
                        )
                        TAB_HISTORY -> ConversationsScreen(modifier)
                        else -> ModelsScreen(modifier)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tab = intent.getIntExtra(EXTRA_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        isAssistant = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_MODELS = 0
        const val TAB_HISTORY = 2
        const val TAB_SETUP = 1
    }
}

@Composable
private fun SetupScreen(isAssistant: Boolean, onOpenSettings: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.setup_body), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(if (isAssistant) R.string.setup_status_active else R.string.setup_status_inactive),
            style = MaterialTheme.typography.titleMedium,
            color = if (isAssistant) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.setup_open_settings)) }
        ScreenContextStatus()
        BrainSettings()
        VoiceSetup()
        LanguagePicker()
    }
}

/** Acceleration and the one feature that can reach the internet, both the user's call. */
@Composable
private fun BrainSettings() {
    val acceleration by AssistantRuntime.engine.acceleration.collectAsState()
    val webSearch by AssistantRuntime.tools.webSearch.collectAsState()
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.brain_title), style = MaterialTheme.typography.titleMedium)
        SettingSwitch(
            label = stringResource(R.string.brain_gpu),
            note = stringResource(if (acceleration.gpuBroken) R.string.brain_gpu_broken else R.string.brain_gpu_note),
            checked = acceleration.useGpu && !acceleration.gpuBroken,
            onChange = AssistantRuntime.engine::setUseGpu,
        )
        SettingSwitch(
            label = stringResource(R.string.brain_web_search),
            note = stringResource(R.string.brain_web_search_note),
            checked = webSearch,
            onChange = AssistantRuntime.tools::setWebSearch,
        )
    }
}

@Composable
private fun SettingSwitch(label: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Android only shares on-screen text when the user turns it on for the assistant. */
@Composable
private fun ScreenContextStatus() {
    val context = LocalContext.current
    val enabled = Settings.Secure.getInt(context.contentResolver, "assist_structure_enabled", 0) == 1
    Text(
        stringResource(if (enabled) R.string.setup_screen_on else R.string.setup_screen_off),
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Per-app language through the platform LocaleManager, also exposed in system settings. */
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    val localeManager = context.getSystemService(LocaleManager::class.java)
    var current by remember { mutableStateOf(localeManager.applicationLocales.toLanguageTags()) }
    val options = listOf(
        "" to stringResource(R.string.setup_language_system),
        "es" to "Español",
        "en" to "English",
    )
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.setup_language), style = MaterialTheme.typography.titleMedium)
        options.forEach { (tag, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        current = tag
                        localeManager.applicationLocales = LocaleList.forLanguageTags(tag)
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == tag, onClick = null)
                Text(label, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}
