package dev.localassistant.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.localassistant.ui.navigation.Destination
import dev.localassistant.ui.theme.AssistantTheme
import dev.localassistant.wake.WakeWordService

class MainActivity : ComponentActivity() {
    private var destination by mutableStateOf(Destination.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        destination = savedInstanceState?.getString(KEY_DESTINATION)?.let(::destinationOf)
            ?: intent.destination()
            ?: Destination.HOME
        setContent {
            AssistantTheme {
                AppShell(destination = destination, onNavigate = { destination = it })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.destination()?.let { destination = it }
    }

    override fun onResume() {
        super.onResume()
        // The wake word can only start listening while the app is in front.
        WakeWordService.syncWithSettings(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DESTINATION, destination.name)
    }

    private fun Intent.destination(): Destination? = getStringExtra(EXTRA_DESTINATION)?.let(::destinationOf)

    companion object {
        private const val EXTRA_DESTINATION = "destination"
        private const val KEY_DESTINATION = "destination"

        private fun destinationOf(name: String) = Destination.entries.firstOrNull { it.name == name }

        /** Opens the app on [destination], from anywhere: the panel, the keyboard, a notification. */
        fun intent(context: Context, destination: Destination): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
