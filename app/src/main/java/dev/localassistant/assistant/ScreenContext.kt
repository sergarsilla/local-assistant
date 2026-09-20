package dev.localassistant.assistant

import android.app.assist.AssistStructure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Text read from whatever was on screen when the assistant opened. Android only provides it when
 * "Use text from screen" is enabled for the assistant, and it is sent to the model only on request.
 */
class ScreenContext {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()

    fun update(structure: AssistStructure?) {
        _text.value = structure?.let(::flatten)?.takeIf { it.isNotBlank() }
    }

    /** Wraps the question with what is on screen, clearly separated so the model can quote it. */
    fun withScreen(question: String, screenText: String?): String {
        if (screenText.isNullOrBlank()) return question
        return buildString {
            appendLine("Text currently on the user's screen:")
            appendLine("---")
            appendLine(screenText)
            appendLine("---")
            appendLine()
            append(question)
        }
    }

    fun clear() {
        _text.value = null
    }

    private fun flatten(structure: AssistStructure): String {
        val builder = StringBuilder()
        for (window in 0 until structure.windowNodeCount) {
            collect(structure.getWindowNodeAt(window).rootViewNode, builder)
        }
        return builder.toString().replace(Regex("\\s*\n\\s*"), "\n").trim().take(MAX_CHARS)
    }

    private fun collect(node: AssistStructure.ViewNode?, out: StringBuilder) {
        if (node == null || out.length > MAX_CHARS) return
        val text = node.text?.toString()?.trim()
        val description = node.contentDescription?.toString()?.trim()
        // Content descriptions repeat visible labels often enough that only new text is kept.
        listOfNotNull(text, description?.takeIf { it != text })
            .filter { it.isNotEmpty() }
            .forEach { out.append(it).append('\n') }
        for (child in 0 until node.childCount) collect(node.getChildAt(child), out)
    }

    private companion object {
        const val MAX_CHARS = 4000
    }
}
