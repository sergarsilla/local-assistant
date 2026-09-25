package dev.localassistant.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.localassistant.R
import dev.localassistant.ui.history.HistoryRoute
import dev.localassistant.ui.home.HomeRoute
import dev.localassistant.ui.models.ModelsRoute
import dev.localassistant.ui.navigation.Destination
import dev.localassistant.ui.settings.SettingsRoute
import dev.localassistant.ui.theme.AssistantIcons
import kotlinx.coroutines.launch

/** The app's frame: the current screen, the bottom navigation and one place for messages. */
@Composable
fun AppShell(destination: Destination, onNavigate: (Destination) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == destination,
                        onClick = { onNavigate(item) },
                        icon = { Icon(item.icon(), contentDescription = null) },
                        label = { Text(stringResource(item.label())) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).consumeWindowInsets(padding)
        when (destination) {
            Destination.HOME -> HomeRoute(modifier, onNavigate = onNavigate, showMessage = showMessage)
            Destination.MODELS -> ModelsRoute(modifier, showMessage = showMessage)
            Destination.HISTORY -> HistoryRoute(modifier, showMessage = showMessage, onOpenSettings = { onNavigate(Destination.SETTINGS) })
            Destination.SETTINGS -> SettingsRoute(modifier, showMessage = showMessage)
        }
    }
}

private fun Destination.icon(): ImageVector = when (this) {
    Destination.HOME -> Icons.Filled.Home
    Destination.MODELS -> AssistantIcons.Memory
    Destination.HISTORY -> AssistantIcons.History
    Destination.SETTINGS -> Icons.Filled.Settings
}

private fun Destination.label(): Int = when (this) {
    Destination.HOME -> R.string.tab_home
    Destination.MODELS -> R.string.tab_models
    Destination.HISTORY -> R.string.tab_history
    Destination.SETTINGS -> R.string.tab_settings
}
