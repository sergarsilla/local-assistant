package dev.localassistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Starts an activity through whoever is allowed to do it right now. */
fun interface ActivityLauncher {
    /** [leavesPanel] is true when the activity shows its own screen, so the panel should close. */
    fun launch(intent: Intent, leavesPanel: Boolean)
}

/** An action the model proposed; it only runs when the user taps it. */
data class PendingAction(val label: String, val intent: Intent)

/** Holds the action waiting for confirmation in the panel. */
class ActionQueue {
    private val _pending = MutableStateFlow<PendingAction?>(null)
    val pending: StateFlow<PendingAction?> = _pending.asStateFlow()

    fun propose(action: PendingAction) {
        _pending.value = action
    }

    fun clear() {
        _pending.value = null
    }

    fun confirm(launcher: ActivityLauncher) {
        val action = _pending.value ?: return
        _pending.value = null
        launcher.launch(action.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), true)
    }
}

/** Timers start straight away: they are trivially reversible and asking would only get in the way. */
class TimerTool(private val launcher: ActivityLauncher, private val listener: ToolActivityListener) : AssistantTool {
    override val name = "set_timer"

    override fun describe() = """
        {"name":"set_timer",
         "description":"Start a countdown timer.",
         "parameters":{"type":"object","properties":{
           "seconds":{"type":"integer"},
           "label":{"type":"string"}},
          "required":["seconds"]}}
    """.trimIndent()

    override fun run(arguments: JSONObject): String {
        val seconds = arguments.optInt("seconds")
        if (seconds !in 1..86_400) return JSONObject().put("error", "seconds out of range").toString()
        val label = arguments.optString("label").ifBlank { null }
        listener.onToolStarted(name, label ?: "${seconds}s")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(launcher, intent, "timer started", leavesPanel = false)
    }
}

class AlarmTool(private val launcher: ActivityLauncher, private val listener: ToolActivityListener) : AssistantTool {
    override val name = "set_alarm"

    override fun describe() = """
        {"name":"set_alarm",
         "description":"Set an alarm at a time of day.",
         "parameters":{"type":"object","properties":{
           "hour":{"type":"integer","description":"0-23"},
           "minute":{"type":"integer","description":"0-59"},
           "label":{"type":"string"}},
          "required":["hour","minute"]}}
    """.trimIndent()

    override fun run(arguments: JSONObject): String {
        val hour = arguments.optInt("hour", -1)
        val minute = arguments.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return JSONObject().put("error", "invalid time").toString()
        val label = arguments.optString("label").ifBlank { null }
        listener.onToolStarted(name, "%02d:%02d".format(hour, minute))
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(launcher, intent, "alarm set for %02d:%02d".format(hour, minute), leavesPanel = false)
    }
}

class OpenAppTool(
    private val context: Context,
    private val launcher: ActivityLauncher,
    private val listener: ToolActivityListener,
) : AssistantTool {
    override val name = "open_app"

    override fun describe() = """
        {"name":"open_app",
         "description":"Open an installed app.",
         "parameters":{"type":"object","properties":{
           "name":{"type":"string","description":"App name."}},
          "required":["name"]}}
    """.trimIndent()

    override fun run(arguments: JSONObject): String {
        val wanted = normalize(arguments.optString("name"))
        if (wanted.isEmpty()) return JSONObject().put("error", "missing name").toString()
        listener.onToolStarted(name, arguments.optString("name"))
        val packages = context.packageManager
        val installed = packages
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to packages.getApplicationLabel(it.activityInfo.applicationInfo).toString() }
            .distinctBy { it.first }
        val match = installed
            .map { it to score(normalize(it.second), it.first, wanted) }
            .filter { (_, score) -> score < Int.MAX_VALUE }
            .minByOrNull { (_, score) -> score }
            ?.first
            // The model may use another language for the app name, so it gets the real list to retry.
            ?: return JSONObject()
                .put("error", "app not found")
                .put("installed_apps", JSONArray(installed.map { it.second }.sorted().take(MAX_LISTED_APPS)))
                .toString()
        val intent = packages.getLaunchIntentForPackage(match.first)
            ?: return JSONObject().put("error", "app cannot be opened").toString()
        return start(launcher, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "opened ${match.second}")
    }

    /**
     * Lower is better. Words of the request that the app name covers weigh most, and extra words
     * in the app name break ties: "google maps" then picks "Maps" over "Google Play Store".
     */
    private fun score(label: String, packageName: String, wanted: String): Int {
        if (label == wanted) return 0
        val wantedWords = wanted.split(' ').filter { it.length >= MIN_WORD }
        val labelWords = label.split(' ', '.', '-').filter { it.isNotEmpty() }
        if (wantedWords.isEmpty() || labelWords.isEmpty()) return Int.MAX_VALUE
        // A word matches when both share a long enough stem, which also covers "calculadora"
        // against "Calculator".
        val matched = wantedWords.count { word ->
            labelWords.any { sharedPrefix(it, word) >= minOf(STEM_LENGTH, word.length, it.length) }
        }
        if (matched == 0) return if (wanted in packageName) MAX_SCORE else Int.MAX_VALUE
        return (wantedWords.size - matched) * WORD_WEIGHT + (labelWords.size - matched) + 1
    }

    private fun sharedPrefix(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }

    private companion object {
        const val STEM_LENGTH = 5
        const val MIN_WORD = 3
        const val WORD_WEIGHT = 10
        const val MAX_SCORE = 100
        const val MAX_LISTED_APPS = 40
    }

    private fun normalize(text: String) = java.text.Normalizer.normalize(text.lowercase().trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
}

/** Calendar entries and messages are only prepared; the user confirms them in the panel. */
class CalendarTool(
    private val queue: ActionQueue,
    private val listener: ToolActivityListener,
) : AssistantTool {
    override val name = "create_calendar_event"

    override fun describe() = """
        {"name":"create_calendar_event",
         "description":"Prepare a calendar event for the user to confirm.",
         "parameters":{"type":"object","properties":{
           "title":{"type":"string"},
           "date":{"type":"string","description":"YYYY-MM-DD"},
           "time":{"type":"string","description":"HH:MM"},
           "duration_minutes":{"type":"integer","description":"default 60"}},
          "required":["title","date","time"]}}
    """.trimIndent()

    override fun run(arguments: JSONObject): String {
        val title = arguments.optString("title").ifBlank { return invalid("missing title") }
        val date = arguments.optString("date")
        val time = arguments.optString("time")
        val start = parse(date, time) ?: return invalid("invalid date or time")
        val minutes = arguments.optInt("duration_minutes", 60).coerceIn(5, 24 * 60)
        listener.onToolStarted(name, "$title · $date $time")
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + minutes * 60_000L)
        queue.propose(PendingAction("$title · $date $time", intent))
        return JSONObject().put("status", "waiting for the user to confirm").toString()
    }

    private fun parse(date: String, time: String): Long? {
        val day = Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(date)?.destructured ?: return null
        val clock = Regex("(\\d{1,2}):(\\d{2})").find(time)?.destructured ?: return null
        val (year, month, dayOfMonth) = day
        val (hour, minute) = clock
        return Calendar.getInstance().apply {
            set(year.toInt(), month.toInt() - 1, dayOfMonth.toInt(), hour.toInt(), minute.toInt(), 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun invalid(reason: String) = JSONObject().put("error", reason).toString()
}

class SendMessageTool(
    private val queue: ActionQueue,
    private val listener: ToolActivityListener,
) : AssistantTool {
    override val name = "send_message"

    override fun describe() = """
        {"name":"send_message",
         "description":"Prepare a text message for the user to confirm.",
         "parameters":{"type":"object","properties":{
           "recipient":{"type":"string"},
           "text":{"type":"string"}},
          "required":["recipient","text"]}}
    """.trimIndent()

    override fun run(arguments: JSONObject): String {
        val recipient = arguments.optString("recipient").ifBlank {
            return JSONObject().put("error", "missing recipient").toString()
        }
        val text = arguments.optString("text").ifBlank {
            return JSONObject().put("error", "missing text").toString()
        }
        listener.onToolStarted(name, recipient)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(recipient)}"))
            .putExtra("sms_body", text)
        queue.propose(PendingAction("$recipient: $text", intent))
        return JSONObject().put("status", "waiting for the user to confirm").toString()
    }
}

/** Navigation and places go to whichever maps app the phone has, Google's or not. */
class MapsTool(
    private val context: Context,
    private val launcher: ActivityLauncher,
    private val listener: ToolActivityListener,
) : AssistantTool {
    override val name = "open_maps"

    override fun describe() = """
        {"name":"open_maps",
         "description":"Show a place on the map or navigate to it.",
         "parameters":{"type":"object","properties":{
           "destination":{"type":"string"},
           "navigate":{"type":"boolean","description":"default true"},
           "mode":{"type":"string","description":"driving|walking|bicycling|transit"}},
          "required":["destination"]}}
    """.trimIndent()

    override fun run(arguments: JSONObject): String {
        val destination = arguments.optString("destination").ifBlank {
            return JSONObject().put("error", "missing destination").toString()
        }
        val navigate = arguments.optBoolean("navigate", true)
        listener.onToolStarted(name, destination)
        val query = Uri.encode(destination)
        val mode = when (arguments.optString("mode")) {
            "walking" -> "w"
            "bicycling" -> "b"
            "transit" -> "r"
            else -> "d"
        }
        // google.navigation is understood by Google Maps; geo: works with any maps app.
        val candidates = buildList {
            if (navigate) add(Uri.parse("google.navigation:q=$query&mode=$mode"))
            add(Uri.parse("geo:0,0?q=$query"))
        }
        val intent = candidates
            .map { Intent(Intent.ACTION_VIEW, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .firstOrNull { it.resolveActivity(context.packageManager) != null }
            ?: return JSONObject().put("error", "no maps app installed").toString()
        return start(launcher, intent, if (navigate) "navigating to $destination" else "showing $destination")
    }
}

private fun start(launcher: ActivityLauncher, intent: Intent, done: String, leavesPanel: Boolean = true): String = runCatching {
    launcher.launch(intent, leavesPanel)
    JSONObject().put("status", done).toString()
}.getOrElse { JSONObject().put("error", it.message ?: "could not start the action").toString() }
