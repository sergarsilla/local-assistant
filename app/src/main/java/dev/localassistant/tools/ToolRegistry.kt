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
     * session lends its own launcher, which is allowed to start activities. The flag says whether
     * the action brings up another app, in which case the panel gets out of the way.
     */
    @Volatile
    var activityStarter: ((intent: Intent, leavesPanel: Boolean) -> Unit)? = null

    private val prefs = context.getSharedPreferences("tools", Context.MODE_PRIVATE)
    private val _webSearch = MutableStateFlow(prefs.getBoolean(KEY_WEB_SEARCH, false))

    /** Off until the user turns it on: nothing leaves the phone unless they decided so. */
    val webSearch: StateFlow<Boolean> = _webSearch.asStateFlow()

    fun setWebSearch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEB_SEARCH, enabled).apply()
        _webSearch.value = enabled
    }

    private val _activity = MutableStateFlow<ToolActivity?>(null)
    val activity: StateFlow<ToolActivity?> = _activity.asStateFlow()

    private val listener = ToolActivityListener { name, summary -> _activity.value = ToolActivity(name, summary) }

    private val launcher = ActivityLauncher { intent, leavesPanel ->
        activityStarter?.invoke(intent, leavesPanel)
            ?: context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    /**
     * Web tools are only offered when the user enabled them and the network permission is on, so
     * the model never promises a search it cannot make.
     */
    fun available(): List<AssistantTool> = onDevice + if (webSearchEnabled()) online else emptyList()

    fun webSearchEnabled() = _webSearch.value && networkAllowed()

    /** Runs the action the user just confirmed, from wherever activity starts are allowed. */
    fun confirmPendingAction() = actions.confirm(launcher)

    fun networkAllowed() =
        context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    fun clearActivity() {
        _activity.value = null
    }

    private companion object {
        const val KEY_WEB_SEARCH = "web_search"
    }
}
