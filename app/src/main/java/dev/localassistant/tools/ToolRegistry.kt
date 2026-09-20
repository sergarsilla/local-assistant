package dev.localassistant.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ToolActivity(val name: String, val summary: String)

/** Everything the model is allowed to do, plus what it is doing right now. */
class ToolRegistry(private val context: Context) {
    val actions = ActionQueue()

    private val _activity = MutableStateFlow<ToolActivity?>(null)
    val activity: StateFlow<ToolActivity?> = _activity.asStateFlow()

    private val listener = ToolActivityListener { name, summary -> _activity.value = ToolActivity(name, summary) }

    private val onDevice = listOf(
        TimerTool(context, listener),
        AlarmTool(context, listener),
        OpenAppTool(context, listener),
        MapsTool(context, listener),
        CalendarTool(actions, listener),
        SendMessageTool(actions, listener),
    )
    private val online = listOf(WebSearchTool(listener), ReadPageTool(listener))

    /** Web tools disappear when the network permission is off, so the model never promises a search. */
    fun available(): List<AssistantTool> = onDevice + if (networkAllowed()) online else emptyList()

    fun networkAllowed() =
        context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    fun clearActivity() {
        _activity.value = null
    }
}
