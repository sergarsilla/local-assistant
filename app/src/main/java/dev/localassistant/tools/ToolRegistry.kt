package dev.localassistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ToolActivity(val name: String, val summary: String)

/** Everything the model is allowed to do, plus what it is doing right now. */
class ToolRegistry(private val context: Context, apps: AppPreferences) {
    val actions = ActionQueue()

    /**
     * Android blocks activity starts from the background, so while the assistant panel is open the
     * session lends its own launcher, which is allowed to start activities. The flag says whether
     * the action brings up another app, in which case the panel gets out of the way.
     */
    @Volatile
    var activityStarter: ((intent: Intent, leavesPanel: Boolean) -> Unit)? = null

    /** Hides the open panel; set by the panel while it is shown. */
    @Volatile
    var hidePanel: (() -> Unit)? = null

    /** Shows the panel again, on the conversation it left. */
    @Volatile
    var reopenPanel: (() -> Unit)? = null

    /**
     * True while a tool has the panel out of the way to look at the app under it: the panel's
     * window covers the whole screen, so nothing below can be read while it is up.
     */
    @Volatile
    var panelSteppedAside = false
        private set

    @Volatile
    private var panelHidden = false

    /** Called by the panel when it is hidden while stepped aside, so it comes back afterwards. */
    fun onPanelHiddenAside() {
        panelHidden = true
    }

    private val stepAside = PanelAside { work ->
        val hide = hidePanel
        panelHidden = false
        panelSteppedAside = hide != null
        hide?.invoke()
        // Reading before the panel is gone would see only the panel, and a turn ending before the
        // hide lands would let that hide cancel the next one.
        val until = SystemClock.elapsedRealtime() + HIDE_TIMEOUT_MS
        while (hide != null && !panelHidden && SystemClock.elapsedRealtime() < until) SystemClock.sleep(HIDE_POLL_MS)
        try {
            work()
        } finally {
            panelSteppedAside = false
            if (panelHidden) {
                panelHidden = false
                reopenPanel?.invoke()
            }
        }
    }

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

    private val contacts = ContactsDirectory(context)
    private val installed = InstalledApps(context)

    private val onDevice = listOf(
        TimerTool(launcher, listener),
        AlarmTool(launcher, listener),
        OpenAppTool(context, launcher, listener),
        MapsTool(apps, installed, launcher, listener),
        CalendarTool(actions, listener),
        SendMessageTool(actions, contacts, apps, installed, listener),
        CallTool(launcher, contacts, listener),
        SiteSearchTool(launcher, listener),
        OpenLinkTool(launcher, listener),
        ReadAppTool(context, launcher, stepAside, listener),
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
        const val HIDE_TIMEOUT_MS = 1_500L
        const val HIDE_POLL_MS = 50L
    }
}
