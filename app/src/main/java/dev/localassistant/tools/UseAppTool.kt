package dev.localassistant.tools

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.localassistant.screen.AppControl
import dev.localassistant.screen.AppReaderService
import dev.localassistant.screen.AppStep
import dev.localassistant.screen.Consequence
import dev.localassistant.screen.StepOutcome
import org.json.JSONObject

/**
 * Does one step inside an app for the user: tap, type, scroll or go back. Every step answers with
 * what the app shows next, so the model can take the following one. A tap that sends, deletes or
 * pays is not done; it waits in the panel for the user to confirm.
 */
class UseAppTool(
    private val context: Context,
    private val launcher: ActivityLauncher,
    private val aside: PanelAside,
    private val actions: ActionQueue,
    private val listener: ToolActivityListener,
) : AssistantTool {
    override val name = "use_app"

    override fun describe() = """
        {"name":"use_app",
         "description":"Do one step in an app for the user: tap a button or item by the words shown on it, type text into a field, scroll, or go back. Returns what the app shows next, so call it again for the next step. Sending, deleting or paying waits for the user to confirm.",
         "parameters":{"type":"object","properties":{
           "action":{"type":"string","enum":["tap","type","scroll_down","scroll_up","back"]},
           "target":{"type":"string","description":"Words shown on the button, item or field."},
           "text":{"type":"string","description":"Text to type."},
           "app":{"type":"string","description":"App to open first, if it is not already on screen."}},
          "required":["action"]}}
    """.trimIndent()

    override fun run(arguments: JSONObject): String {
        val reader = AppReaderService.instance
            ?: return error(
                "using other apps is turned off; tell the user to turn on Read and use other apps in the " +
                    "assistant's Settings, under Permissions",
            )
        val step = stepOf(arguments) ?: return error("say what to tap, or the text to type")
        val wanted = arguments.optString("app").trim()
        val target = if (wanted.isEmpty()) null else InstalledLaunchers.find(context, wanted)
        if (wanted.isNotEmpty() && target == null) return error("app not found: $wanted")
        listener.onToolStarted(name, summaryOf(step))
        val own = context.packageName
        return aside.during {
            val control = AppControl(reader, own)
            target?.let { (pkg, label) ->
                val before = control.topPackage()
                if (before != pkg) {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?: return@during error("$label cannot be opened")
                    runCatching { launcher.launch(intent, leavesPanel = false) }
                        .onFailure { return@during error("could not open $label") }
                    // Some apps show screens from another package (Settings' search), so any change counts.
                    poll(OPEN_TIMEOUT_MS) { control.topPackage()?.takeIf { it == pkg || it != before } }
                        ?: return@during error("$label did not open")
                }
            }
            val pkg = poll(SETTLE_MS) { control.topPackage() } ?: return@during error("no app is open")
            // A screen from a helper package the app cannot see (Settings' search) goes by the app's name.
            val app = labelOf(pkg) ?: target?.second ?: pkg
            if (step is AppStep.Tap) {
                val label = control.labelOf(step.target)
                    ?: return@during notFound(control, step.target)
                Consequence.of(label)?.let { kind -> return@during propose(kind, label, app, pkg, step) }
            }
            when (val outcome = control.run(step)) {
                is StepOutcome.Done -> {
                    // The app needs a moment to draw what the step changed, longer when it opens a new screen.
                    SystemClock.sleep(SETTLE_MS)
                    val screen = poll(OPEN_TIMEOUT_MS) { reader.read(own) }
                    JSONObject()
                        .put("done", outcome.what)
                        .put("app", app)
                        .put("screen", screen?.text.orEmpty().take(MAX_SCREEN))
                        .put("numbers", COPY_NUMBERS)
                        .put(RESOLVED, app)
                        .toString()
                }
                is StepOutcome.NotFound -> notFound(control, outcome.what)
                is StepOutcome.Refused -> error(outcome.why)
            }
        }
    }

    private fun propose(kind: Consequence.Kind, label: String, app: String, pkg: String, step: AppStep): String {
        val own = context.packageName
        actions.propose(
            PendingAction(
                kind = when (kind) {
                    Consequence.Kind.SEND -> ActionKind.SEND
                    Consequence.Kind.DELETE -> ActionKind.DELETE
                    Consequence.Kind.PAY -> ActionKind.PAY
                },
                detail = "«$label» · $app",
                perform = perform@{
                    val reader = AppReaderService.instance ?: return@perform
                    val control = AppControl(reader, own)
                    // Only the app the user approved, in case another came to the front meanwhile.
                    if (poll(SETTLE_MS) { control.topPackage()?.takeIf { it == pkg } } == null) {
                        Log.w(TAG, "Confirmed step skipped: its app is no longer on screen")
                        return@perform
                    }
                    control.run(step)
                },
            ),
        )
        return JSONObject()
            .put("waiting_for_user", "the user must confirm «$label» in $app on the card in the panel")
            .put("app", app)
            .toString()
    }

    // The screen may be in another language than the user's words, so the model gets the labels
    // to choose from and is told to try again rather than answer.
    private fun notFound(control: AppControl, what: String) = JSONObject()
        .put(
            "error",
            "nothing on screen is labelled \"$what\". Call use_app again with the label below that means " +
                "the same, written exactly as shown; do not answer yet",
        )
        .put("labels", org.json.JSONArray(control.tappableLabels()))
        .toString()

    private fun stepOf(arguments: JSONObject): AppStep? {
        val target = arguments.optString("target").trim()
        return when (arguments.optString("action").trim().lowercase()) {
            "tap" -> target.takeIf { it.isNotEmpty() }?.let(AppStep::Tap)
            "type" -> arguments.optString("text").takeIf { it.isNotEmpty() }
                ?.let { AppStep.Type(it, target.ifEmpty { null }) }
            "scroll_down" -> AppStep.Scroll(forward = true)
            "scroll_up" -> AppStep.Scroll(forward = false)
            "back" -> AppStep.Back
            else -> null
        }
    }

    private fun summaryOf(step: AppStep) = when (step) {
        is AppStep.Tap -> step.target
        is AppStep.Type -> step.field ?: step.text
        is AppStep.Scroll -> if (step.forward) "↓" else "↑"
        AppStep.Back -> "←"
    }

    private fun labelOf(pkg: String): String? = runCatching {
        val packages = context.packageManager
        packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    private fun error(message: String) = JSONObject().put("error", message).toString()

    private companion object {
        const val TAG = "UseAppTool"
        const val SETTLE_MS = 800L
        const val MAX_SCREEN = 2_500
    }
}
