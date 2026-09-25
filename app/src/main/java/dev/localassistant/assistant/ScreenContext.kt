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

    private val _attach = MutableStateFlow(false)

    /** The panel's "Screen" chip: send what is on screen with every question. */
    val attach: StateFlow<Boolean> = _attach.asStateFlow()

    fun setAttach(value: Boolean) {
        _attach.value = value
    }

    /**
     * The screen text to send with [question]: always when the chip is on, and also when the
     * question itself is about the screen ("resume esto", "what does this say"), so asking out
     * loud works without touching anything.
     */
    fun textFor(question: String): String? {
        val current = _text.value ?: return null
        return current.takeIf { _attach.value || MENTIONS_SCREEN.containsMatchIn(question) }
    }

    fun update(structure: AssistStructure?) {
        _text.value = structure?.let(::flatten)?.takeIf { it.isNotBlank() }
    }

    fun clear() {
        _text.value = null
        _attach.value = false
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
        val MENTIONS_SCREEN = Regex(
            "\\b(pantalla|esto|esta p[aá]gina|este (texto|art[ií]culo|mensaje|correo)|lo que (pone|dice|veo|hay)|" +
                "res[uú]me(lo|me)?|resumen|trad[uú]ce(lo|me)?|screen|this (page|text|article|message|email)|" +
                "what does (this|it) say|summari[sz]e|translate this)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}
