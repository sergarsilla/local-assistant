package dev.localassistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ToolActivity(val name: String, val summary: String)

/** Everything the model is allowed to do, plus what it is doing right now. */
class ToolRegistry(private val context: Context) {
    val actions = ActionQueue()

    /**
     * Android blocks activity starts from the background, so while the assistant panel is open the
     * session lends its own launcher, which is allowed to start activities.
     */
    @Volatile
    var activityStarter: ((Intent) -> Unit)? = null

    private val _activity = MutableStateFlow<ToolActivity?>(null)
    val activity: StateFlow<ToolActivity?> = _activity.asStateFlow()

    private val listener = ToolActivityListener { name, summary -> _activity.value = ToolActivity(name, summary) }

    private val launcher = ActivityLauncher { intent ->
        activityStarter?.invoke(intent) ?: context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private val onDevice = listOf(
        TimerTool(launcher, listener),
        AlarmTool(launcher, listener),
        OpenAppTool(context, launcher, listener),
        MapsTool(context, launcher, listener),
        CalendarTool(actions, listener),
        SendMessageTool(actions, listener),
    )
    private val online = listOf(WebSearchTool(listener), ReadPageTool(listener))

    /** Web tools disappear when the network permission is off, so the model never promises a search. */
    fun available(): List<AssistantTool> = onDevice + if (networkAllowed()) online else emptyList()

    /** Runs the action the user just confirmed, from wherever activity starts are allowed. */
    fun confirmPendingAction() = actions.confirm(launcher)

    fun networkAllowed() =
        context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    fun clearActivity() {
        _activity.value = null
    }
}
