package dev.localassistant.screen

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.lang.ref.WeakReference

/** The text of an app's window, as the user sees it. */
data class AppScreen(val packageName: String, val text: String)

/**
 * Reads the text of the app under the assistant's panel when the user asks it to. It listens to
 * nothing in the background, so it costs nothing until it is asked: the system only binds it.
 */
class AppReaderService : AccessibilityService() {

    override fun onServiceConnected() {
        current = WeakReference(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        current = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * The topmost app window that is not [ownPackage], so the panel on top of it is skipped, or
     * only [onlyPackage] when given. Null when no such window is on screen.
     */
    fun read(ownPackage: String, onlyPackage: String? = null): AppScreen? {
        val candidates = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
        for (window in candidates) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == ownPackage || (onlyPackage != null && pkg != onlyPackage)) continue
            val text = ScreenText.from(root)
            if (text.isNotBlank()) return AppScreen(pkg, text)
        }
        return null
    }

    companion object {
        @Volatile
        private var current: WeakReference<AppReaderService>? = null

        val instance: AppReaderService? get() = current?.get()

        /** Whether the user turned the service on, which is not the same as it being bound yet. */
        fun isEnabled(context: Context): Boolean {
            val wanted = ComponentName(context, AppReaderService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return enabled?.split(':')?.any { it.equals(wanted, ignoreCase = true) } == true
        }
    }
}

/** Visible text of a window, in reading order, without the labels that only repeat it. */
internal object ScreenText {
    const val MAX_CHARS = 6000

    fun from(root: AccessibilityNodeInfo): String {
        val lines = mutableListOf<String>()
        collect(root, lines)
        return join(lines)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null || out.sumOf { it.length } > MAX_CHARS) return
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        // Passwords are read as dots by the system anyway; they are not passed on at all.
        if (!node.isPassword) {
            text?.let(out::add)
            description?.takeIf { it != text }?.let(out::add)
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out)
    }

    /** Trims, drops blanks and lines repeated back to back, and caps the length. */
    fun join(lines: List<String>): String {
        val kept = mutableListOf<String>()
        for (raw in lines) {
            val line = raw.trim().replace(Regex("\\s+"), " ")
            if (line.isEmpty() || line == kept.lastOrNull()) continue
            kept += line
        }
        return kept.joinToString("\n").take(MAX_CHARS)
    }
}
