package dev.localassistant.tools

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.localassistant.screen.AppReaderService
import org.json.JSONObject

/**
 * Reads what an app shows, opening it first when one is named. The app opens behind the panel,
 * which stays up, so the answer comes back in the same conversation instead of the panel closing
 * on the way.
 */
class ReadAppTool(
    private val context: Context,
    private val launcher: ActivityLauncher,
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
            val screen = reader.read(own) ?: return error("no app is open under the assistant")
            return result(screen.packageName, screen.text)
        }
        val installed = InstalledLaunchers.list(context)
        val (pkg, label) = AppMatcher.best(wanted, installed) ?: return error("app not found: $wanted")
        listener.onToolStarted(name, label)
        reader.read(own, onlyPackage = pkg)?.let { return result(label, it.text) }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return error("$label cannot be opened")
        runCatching { launcher.launch(intent, leavesPanel = false) }
            .onFailure { return error("could not open $label") }
        // An app opening from cold takes a few seconds to draw anything worth reading.
        val deadline = SystemClock.elapsedRealtime() + OPEN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_MS)
            reader.read(own, onlyPackage = pkg)?.let { return result(label, it.text) }
        }
        return error("$label opened but showed nothing readable")
    }

    private fun result(app: String, text: String) =
        JSONObject().put("app", app).put("text", text).put(RESOLVED, app).toString()

    private fun error(message: String) = JSONObject().put("error", message).toString()

    private companion object {
        const val OPEN_TIMEOUT_MS = 6_000L
        const val POLL_MS = 300L
    }
}

/** Installed apps with a launcher icon, as (package, label). */
object InstalledLaunchers {
    fun list(context: Context): List<Pair<String, String>> {
        val packages = context.packageManager
        return packages
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to packages.getApplicationLabel(it.activityInfo.applicationInfo).toString() }
            .distinctBy { it.first }
    }
}
