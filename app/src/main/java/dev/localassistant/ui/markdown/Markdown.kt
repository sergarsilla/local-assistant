package dev.localassistant.ui.markdown

/**
 * The subset of Markdown small models actually write in chat replies: paragraphs, headings,
 * bullet and numbered lists, quotes, fenced code, and inline bold, italic, code and links.
 *
 * Replies are parsed while they stream, so the parser never fails: a marker that has not been
 * closed yet stays as plain text until its partner arrives.
 */
object Markdown {

    sealed interface Block {
        data class Paragraph(val inlines: List<Inline>) : Block
        data class Heading(val level: Int, val inlines: List<Inline>) : Block
        data class ListItem(val marker: String, val depth: Int, val inlines: List<Inline>) : Block
        data class Quote(val inlines: List<Inline>) : Block
        data class Code(val text: String) : Block
    }

    data class Inline(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val url: String? = null,
    )

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val QUOTE = Regex("^>\\s?(.*)$")
    private val FENCE = Regex("^\\s*```")
    private val LINK = Regex("^\\[([^\\]]+)]\\(([^)\\s]+)\\)")

    fun parse(source: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(inlines(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }

        val lines = source.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (FENCE.containsMatchIn(line)) {
                flushParagraph()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !FENCE.containsMatchIn(lines[i])) code += lines[i++]
                blocks += Block.Code(code.joinToString("\n"))
                i++ // Skips the closing fence, or runs past the end while the reply is streaming.
                continue
            }
            val heading = HEADING.find(line)
            val bullet = BULLET.find(line)
            val numbered = NUMBERED.find(line)
            val quote = QUOTE.find(line)
            when {
                line.isBlank() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    blocks += Block.Heading(heading.groupValues[1].length, inlines(heading.groupValues[2]))
                }
                bullet != null -> {
                    flushParagraph()
                    blocks += Block.ListItem("•", depth(bullet.groupValues[1]), inlines(bullet.groupValues[2]))
                }
                numbered != null -> {
                    flushParagraph()
                    blocks += Block.ListItem(
                        numbered.groupValues[2] + ".",
                        depth(numbered.groupValues[1]),
                        inlines(numbered.groupValues[3]),
                    )
                }
                quote != null -> {
                    flushParagraph()
                    blocks += Block.Quote(inlines(quote.groupValues[1]))
                }
                else -> paragraph += line.trimEnd()
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    /** Plain text with the markup removed, for speaking or copying a reply. */
    fun plainText(source: String): String = parse(source).joinToString("\n") { block ->
        when (block) {
            is Block.Paragraph -> block.inlines.text()
            is Block.Heading -> block.inlines.text()
            is Block.ListItem -> "${block.marker} ${block.inlines.text()}"
            is Block.Quote -> block.inlines.text()
            is Block.Code -> block.text
        }
    }

    private fun List<Inline>.text() = joinToString("") { it.text }

    private fun depth(indent: String) = (indent.replace("\t", "    ").length / 2).coerceAtMost(3)

    /** Inline markup. Unclosed markers are kept as literal text. */
    fun inlines(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        val plain = StringBuilder()
        var bold = false
        var italic = false
        fun flush() {
            if (plain.isNotEmpty()) {
                out += Inline(plain.toString(), bold = bold, italic = italic)
                plain.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            when {
                rest.startsWith("`") -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        out += Inline(text.substring(i + 1, end), code = true)
                        i = end + 1
                    } else {
                        plain.append('`')
                        i++
                    }
                }
                rest.startsWith("**") || rest.startsWith("__") -> {
                    val marker = rest.substring(0, 2)
                    if (bold || text.indexOf(marker, i + 2) > i) {
                        flush()
                        bold = !bold
                    } else {
                        plain.append(marker)
                    }
                    i += 2
                }
                (rest.startsWith("*") || rest.startsWith("_")) && opensOrClosesItalic(text, i, italic) -> {
                    flush()
                    italic = !italic
                    i++
                }
                rest.startsWith("[") -> {
                    val link = LINK.find(rest)
                    if (link != null) {
                        flush()
                        out += Inline(link.groupValues[1], bold = bold, italic = italic, url = link.groupValues[2])
                        i += link.value.length
                    } else {
                        plain.append('[')
                        i++
                    }
                }
                else -> {
                    plain.append(text[i])
                    i++
                }
            }
        }
        flush()
        return out
    }

    /**
     * A single `*` or `_` toggles italics only when it hugs a word: `3 * 4` and `snake_case`
     * stay as they are.
     */
    private fun opensOrClosesItalic(text: String, index: Int, italicOpen: Boolean): Boolean {
        val marker = text[index]
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + 1)
        if (italicOpen) return before != null && !before.isWhitespace()
        val opens = after != null && !after.isWhitespace() && (before == null || !before.isLetterOrDigit())
        return opens && text.indexOf(marker, index + 1) > index
    }
}
