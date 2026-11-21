package dev.localassistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlin.concurrent.thread
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

    /** Lets a panel that was moved aside go for good, as if the user had closed it. */
    @Volatile
    var releasePanel: (() -> Unit)? = null

    /** Whether a tool that moved the panel aside found something the user should see in it. */
    @Volatile
    private var answerToShow = false

    @Volatile
    private var panelVisible = false

    /**
     * True from the moment a tool moves the panel aside until the turn ends. The panel's window
     * covers the whole screen, so nothing below can be read or touched while it is up; while this
     * is true, the panel being hidden must not end the turn.
     */
    @Volatile
    var panelAway = false
        private set

    fun onPanelShown() {
        panelVisible = true
        panelAway = false
    }

    fun onPanelHidden() {
        panelVisible = false
    }

    /** Moves the panel aside for the rest of the turn, so the app below can be seen and used. */
    private val stepAside = PanelAside { work ->
        val hide = hidePanel
        if (!panelAway && panelVisible && hide != null) {
            panelAway = true
            answerToShow = false
            hide()
            awaitPanelGone()
        }
        work()
    }

    /** As [stepAside], for a tool whose result the user will want to read in the panel. */
    private val stepAsideToAnswer = PanelAside { work -> stepAside.during(work).also { answerToShow = true } }

    /**
     * Called when a turn ends. A panel moved aside comes back if the turn found something to show,
     * such as what an app said; after only opening an app it stays out of the way.
     */
    fun endTurn() {
        if (!panelAway) return
        panelAway = false
        if (answerToShow) reopenPanel?.invoke() else releasePanel?.invoke()
        answerToShow = false
    }

    private fun awaitPanelGone() {
        val until = SystemClock.elapsedRealtime() + HIDE_TIMEOUT_MS
        while (panelVisible && SystemClock.elapsedRealtime() < until) SystemClock.sleep(HIDE_POLL_MS)
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
        OpenAppTool(context, launcher, stepAside, listener),
        MapsTool(apps, installed, launcher, listener),
        CalendarTool(actions, listener),
        SendMessageTool(actions, contacts, apps, installed, listener),
        CallTool(launcher, contacts, listener),
        SiteSearchTool(launcher, listener),
        OpenLinkTool(launcher, listener),
        ReadAppTool(context, launcher, stepAsideToAnswer, listener),
        UseAppTool(context, launcher, stepAsideToAnswer, actions, listener),
    )
    private val online = listOf(WebSearchTool(listener), ReadPageTool(listener))

    /**
     * Web tools are only offered when the user enabled them and the network permission is on, so
     * the model never promises a search it cannot make.
     */
    fun available(): List<AssistantTool> = onDevice + if (webSearchEnabled()) online else emptyList()

    fun webSearchEnabled() = _webSearch.value && networkAllowed()

    /** Runs the action the user just confirmed, from wherever activity starts are allowed. */
    /**
     * An in-app step runs with the panel closed for good: the turn is over, and the user stays in
     * the app to see what happened.
     */
    fun confirmPendingAction() = actions.confirm(launcher) { perform ->
        val hide = hidePanel
        thread(name = "in-app action") {
            hide?.invoke()
            awaitPanelGone()
            perform()
        }
    }

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
