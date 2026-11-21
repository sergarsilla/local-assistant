package dev.localassistant.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.SystemClock
import dev.localassistant.screen.AppReaderService
import dev.localassistant.screen.AppScreen
import org.json.JSONObject
import java.util.Locale

/**
 * Reads what an app shows, opening it first when one is named. The panel steps aside while it
 * reads and comes back with the answer in the same conversation.
 */
class ReadAppTool(
    private val context: Context,
    private val launcher: ActivityLauncher,
    private val aside: PanelAside,
    private val listener: ToolActivityListener,
) : AssistantTool {
    override val name = "read_app"

    override fun describe() = """
        {"name":"read_app",
         "description":"Read the text an app shows, e.g. the latest chats in WhatsApp. Opens the app first if named; without a name reads the app under the assistant.",
         "parameters":{"type":"object","properties":{
           "app":{"type":"string","description":"App name, optional."}},
          "required":[]}}
    """.trimIndent()

    override fun run(arguments: JSONObject): String {
        val reader = AppReaderService.instance
            ?: return error(
                "reading other apps is turned off; tell the user to turn on Read other apps in the assistant's " +
                    "Settings, under Permissions",
            )
        val wanted = arguments.optString("app").trim()
        val own = context.packageName
        if (wanted.isEmpty()) {
            listener.onToolStarted(name, "")
            return aside.during {
                val screen = poll(SETTLE_TIMEOUT_MS) { reader.read(own) }
                    ?: return@during error("no app is open under the assistant")
                result(screen.packageName, screen.text)
            }
        }
        val (pkg, label) = InstalledLaunchers.find(context, wanted) ?: return error("app not found: $wanted")
        listener.onToolStarted(name, label)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return error("$label cannot be opened")
        return aside.during {
            runCatching { launcher.launch(intent, leavesPanel = false) }
                .onFailure { return@during error("could not open $label") }
            // An app opening from cold takes a few seconds to draw anything worth reading.
            val screen = poll(OPEN_TIMEOUT_MS) { reader.read(own, onlyPackage = pkg) }
                ?: return@during error("$label opened but showed nothing readable")
            result(label, screen.text)
        }
    }

    private fun poll(timeoutMs: Long, read: () -> AppScreen?): AppScreen? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_MS)
            read()?.let { return it }
        }
        return null
    }

    private fun result(app: String, text: String) =
        JSONObject().put("app", app).put("text", text).put(RESOLVED, app).toString()

    private fun error(message: String) = JSONObject().put("error", message).toString()

    private companion object {
        const val OPEN_TIMEOUT_MS = 6_000L
        const val SETTLE_TIMEOUT_MS = 2_000L
        const val POLL_MS = 300L
    }
}

/** Runs work that needs the panel out of the way, bringing it back afterwards. */
fun interface PanelAside {
    fun during(work: () -> String): String
}

/** Installed apps with a launcher icon, as (package, label). */
object InstalledLaunchers {
    /** Languages the assistant is spoken to in, whatever language the phone itself uses. */
    private val SPOKEN = listOf(Locale.forLanguageTag("es"), Locale.ENGLISH)

    fun list(context: Context): List<Pair<String, String>> {
        val packages = context.packageManager
        return launchers(context)
            .map { it.packageName to packages.getApplicationLabel(it).toString() }
            .distinctBy { it.first }
    }

    /**
     * The app the user means. Labels follow the phone's language, which need not be the one the
     * user speaks: "ajustes" has to find "Settings" on a phone set to English.
     */
    fun find(
        context: Context,
        name: String,
        installed: List<Pair<String, String>> = list(context),
    ): Pair<String, String>? {
        AppMatcher.best(name, installed)?.let { match ->
            if (AppMatcher.normalize(match.second) == AppMatcher.normalize(name)) return match
        }
        return AppMatcher.best(name, installed + SPOKEN.flatMap { labelsIn(context, it) })
    }

    private fun labelsIn(context: Context, locale: Locale): List<Pair<String, String>> {
        val packages = context.packageManager
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return launchers(context).mapNotNull { app ->
            if (app.labelRes == 0) return@mapNotNull null
            runCatching { packages.getResourcesForApplication(app, config).getString(app.labelRes) }
                .getOrNull()
                ?.let { app.packageName to it }
        }.distinctBy { it.first }
    }

    private fun launchers(context: Context): List<ApplicationInfo> =
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
}
