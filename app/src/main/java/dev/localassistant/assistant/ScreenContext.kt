package dev.localassistant.assistant

import android.app.assist.AssistStructure
import android.graphics.Bitmap
import dev.localassistant.llm.ScreenAttachment
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What was on screen when the assistant opened: its text, and a screenshot. Android only provides
 * them when "Use text from screen" / "Use screenshot" are enabled for the assistant, and they are
 * sent to the model only when the user asks about the screen.
 */
class ScreenContext {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()

    private val _image = MutableStateFlow<ByteArray?>(null)

    /** The screenshot as a JPEG small enough for a vision encoder. */
    val image: StateFlow<ByteArray?> = _image.asStateFlow()

    private val _attach = MutableStateFlow(false)

    /** The panel's "Screen" chip: send what is on screen with every question. */
    val attach: StateFlow<Boolean> = _attach.asStateFlow()

    fun setAttach(value: Boolean) {
        _attach.value = value
    }

    /**
     * What to send with [question]: always when the chip is on, and also when the question
     * itself is about the screen ("resume esto", "what does this say"), so asking out loud works
     * without touching anything.
     */
    fun attachmentFor(question: String): ScreenAttachment? {
        val text = _text.value
        val image = _image.value
        if (text == null && image == null) return null
        if (!_attach.value && !mentionsScreen(question)) return null
        return ScreenAttachment(text, image)
    }

    /** Scales the screenshot down and compresses it; call off the main thread. */
    fun updateScreenshot(screenshot: Bitmap?) {
        _image.value = screenshot?.let(::encode)
    }

    fun update(structure: AssistStructure?) {
        _text.value = structure?.let(::flatten)?.takeIf { it.isNotBlank() }
    }

    fun clear() {
        _text.value = null
        _image.value = null
        _attach.value = false
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
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

    companion object {
        private const val MAX_CHARS = 4000
        private const val MAX_IMAGE_SIDE = 768
        private const val JPEG_QUALITY = 85

        /** True when [question] refers to what is on screen. */
        fun mentionsScreen(question: String) = MENTIONS_SCREEN.containsMatchIn(question)

        private val MENTIONS_SCREEN = Regex(
            "\\b(pantalla|esto|esta p[aá]gina|este (texto|art[ií]culo|mensaje|correo)|lo que (pone|dice|veo|hay)|" +
                "res[uú]me(lo|me)?|resumen|trad[uú]ce(lo|me)?|screen|this (page|text|article|message|email)|" +
                "what does (this|it) say|summari[sz]e|translate this)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}
