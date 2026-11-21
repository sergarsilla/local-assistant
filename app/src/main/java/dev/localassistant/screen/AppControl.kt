package dev.localassistant.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.text.Normalizer

/** One step the assistant can take inside the app on screen. */
sealed interface AppStep {
    data class Tap(val target: String) : AppStep
    data class Type(val text: String, val field: String?) : AppStep
    data class Scroll(val forward: Boolean) : AppStep
    data object Back : AppStep
}

/** What a step found on screen, before or after doing it. */
sealed interface StepOutcome {
    data class Done(val what: String) : StepOutcome
    data class NotFound(val what: String) : StepOutcome
    data class Refused(val why: String) : StepOutcome
}

/**
 * Finds things in the topmost app window by the text the user sees and acts on them the way a
 * finger would. Only ever touches a window of another app, never the assistant's own.
 */
class AppControl(private val service: AccessibilityService, private val ownPackage: String) {

    fun topPackage(): String? = topRoot()?.packageName?.toString()

    /** The visible label of what a tap on [target] would press, or null when nothing matches. */
    fun labelOf(target: String): String? = findByLabel(target) { true }?.let(::labelOf)

    /**
     * The words on everything that can be tapped, so the model can pick one when the user's words
     * do not match the screen, such as "Batería" on a phone set to English.
     */
    fun tappableLabels(): List<String> {
        val root = topRoot() ?: return emptyList()
        return nodes(root)
            .filter { it.isVisibleToUser && it.isClickable }
            .mapNotNull { node -> labelOf(node) ?: firstLabelBelow(node) }
            .distinct()
            .take(MAX_LABELS)
    }

    fun run(step: AppStep): StepOutcome = when (step) {
        is AppStep.Tap -> tap(step.target)
        is AppStep.Type -> type(step.text, step.field)
        is AppStep.Scroll -> scroll(step.forward)
        AppStep.Back -> if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            StepOutcome.Done("went back")
        } else {
            StepOutcome.Refused("could not go back")
        }
    }

    private fun tap(target: String): StepOutcome {
        val node = findByLabel(target) { true } ?: return StepOutcome.NotFound(target)
        val label = labelOf(node) ?: target
        var pressable: AccessibilityNodeInfo? = node
        while (pressable != null && !pressable.isClickable) pressable = pressable.parent
        if (pressable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return StepOutcome.Done("tapped $label")
        // Some apps draw their own buttons without saying they can be clicked; a real tap still works.
        val bounds = Rect().also(node::getBoundsInScreen)
        return if (gesture(bounds.centerX().toFloat(), bounds.centerY().toFloat())) {
            StepOutcome.Done("tapped $label")
        } else {
            StepOutcome.Refused("could not tap $label")
        }
    }

    private fun type(text: String, field: String?): StepOutcome {
        val root = topRoot() ?: return StepOutcome.NotFound("an app on screen")
        val node = if (field.isNullOrBlank()) {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: nodes(root).firstOrNull { it.isEditable }
        } else {
            findByLabel(field) { it.isEditable }
        } ?: return StepOutcome.NotFound(field ?: "a text field")
        if (node.isPassword) return StepOutcome.Refused("that is a password field")
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            StepOutcome.Done("typed into ${labelOf(node) ?: "the field"}")
        } else {
            StepOutcome.Refused("the field did not accept text")
        }
    }

    private fun scroll(forward: Boolean): StepOutcome {
        val root = topRoot() ?: return StepOutcome.NotFound("an app on screen")
        val list = nodes(root)
            .filter { it.isScrollable }
            .maxByOrNull { Rect().also(it::getBoundsInScreen).let { r -> r.width() * r.height() } }
            ?: return StepOutcome.NotFound("anything to scroll")
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (list.performAction(action)) {
            StepOutcome.Done(if (forward) "scrolled down" else "scrolled up")
        } else {
            StepOutcome.Refused("it does not scroll further")
        }
    }

    private fun findByLabel(target: String, accept: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = topRoot() ?: return null
        val ranked = nodes(root)
            .filter { it.isVisibleToUser && accept(it) }
            .mapNotNull { node -> labelOf(node)?.let { label -> Triple(node, label, LabelMatch.score(label, target)) } }
            .filter { (_, _, score) -> score < LabelMatch.NONE }
            .sortedBy { (_, _, score) -> score }
        val best = ranked.firstOrNull() ?: return null
        // A near spelling is only trusted when nothing else on screen is spelt as near.
        val rival = ranked.drop(1).firstOrNull { (_, label, score) -> score == best.third && label != best.second }
        return if (best.third >= LabelMatch.COGNATE && rival != null) null else best.first
    }

    private fun labelOf(node: AccessibilityNodeInfo): String? =
        listOf(node.text, node.contentDescription, node.hintText)
            .firstOrNull { !it.isNullOrBlank() }
            ?.toString()
            ?.trim()

    private fun firstLabelBelow(node: AccessibilityNodeInfo): String? =
        nodes(node).drop(1).firstNotNullOfOrNull { child -> child.takeIf { it.isVisibleToUser }?.let(::labelOf) }

    private fun topRoot(): AccessibilityNodeInfo? =
        service.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() != ownPackage }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val node = queue.removeFirst()
            out += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return out
    }

    private fun gesture(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_MS)
        return service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private companion object {
        const val MAX_NODES = 2_000
        const val MAX_LABELS = 40
        const val TAP_MS = 50L
    }
}

/** How well a label on screen matches the words the user or the model used for it. */
internal object LabelMatch {
    const val NONE = Int.MAX_VALUE
    const val COGNATE = 1_000

    /**
     * Lower is better: an exact label, then one that starts with the target, then one containing
     * it, and last a word spelt almost the same. The last covers the user speaking Spanish to a
     * phone set to English: "batería" finds "Battery", "notificaciones" finds "Notifications".
     */
    fun score(label: String, target: String): Int {
        val have = normalize(label)
        val want = normalize(target)
        if (have.isEmpty() || want.isEmpty()) return NONE
        return when {
            have == want -> 0
            have.startsWith("$want ") -> 1 + have.length - want.length
            Regex("\\b${Regex.escape(want)}\\b").containsMatchIn(have) -> 100 + have.length - want.length
            else -> cognate(have, want)
        }
    }

    private fun cognate(have: String, want: String): Int {
        // At most one edit for every two letters: "bateria" and "battery" are 3 apart in 7.
        if (' ' in want || want.length < MIN_COGNATE) return NONE
        val best = have.split(' ')
            .filter { it.length >= MIN_COGNATE }
            .minOfOrNull { word -> distance(word, want).takeIf { it * 2 <= maxOf(word.length, want.length) } ?: NONE }
            ?: NONE
        return if (best == NONE) NONE else COGNATE + best * 10 + have.length
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            previous = current
        }
        return previous[b.length]
    }

    private const val MIN_COGNATE = 5

    fun normalize(text: String) =
        Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}

/**
 * Which taps have consequences the user must approve first: sending something, deleting
 * something, or paying. Judged by the words on the button, in Spanish and English.
 */
internal object Consequence {
    enum class Kind { SEND, DELETE, PAY }

    private val WORDS = mapOf(
        Kind.SEND to setOf(
            "enviar", "mandar", "publicar", "compartir",
            "send", "post", "share", "publish", "tweet", "reply all",
        ),
        Kind.DELETE to setOf(
            "borrar", "eliminar", "vaciar", "papelera", "desinstalar", "quitar",
            "delete", "remove", "erase", "trash", "uninstall", "clear",
        ),
        Kind.PAY to setOf(
            "pagar", "comprar", "comprar ahora", "tramitar pedido", "realizar pedido",
            "confirmar pedido", "suscribirse", "suscribete", "donar",
            "pay", "buy", "buy now", "purchase", "checkout", "check out", "place order", "subscribe", "donate",
        ),
    )

    fun of(label: String): Kind? {
        val words = " ${LabelMatch.normalize(label)} "
        return WORDS.entries.firstOrNull { (_, terms) -> terms.any { " $it " in words } }?.key
    }
}
