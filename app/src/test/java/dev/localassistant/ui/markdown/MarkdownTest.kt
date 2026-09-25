package dev.localassistant.ui.markdown

import dev.localassistant.ui.markdown.Markdown.Block
import dev.localassistant.ui.markdown.Markdown.Inline
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test
    fun `paragraphs, headings, lists and quotes become blocks`() {
        val blocks = Markdown.parse("# Title\n\nFirst line\nsecond line\n\n- one\n  - nested\n2. two\n> quoted")
        assertEquals(
            listOf(
                Block.Heading(1, listOf(Inline("Title"))),
                Block.Paragraph(listOf(Inline("First line\nsecond line"))),
                Block.ListItem("•", 0, listOf(Inline("one"))),
                Block.ListItem("•", 1, listOf(Inline("nested"))),
                Block.ListItem("2.", 0, listOf(Inline("two"))),
                Block.Quote(listOf(Inline("quoted"))),
            ),
            blocks,
        )
    }

    @Test
    fun `fenced code keeps its text and survives a missing closing fence while streaming`() {
        assertEquals(listOf(Block.Code("val x = 1\nx * 2")), Markdown.parse("```kotlin\nval x = 1\nx * 2\n```"))
        assertEquals(listOf(Block.Code("still coming")), Markdown.parse("```\nstill coming"))
    }

    @Test
    fun `inline bold, italic, code and links`() {
        assertEquals(
            listOf(
                Inline("A "),
                Inline("bold", bold = true),
                Inline(" and "),
                Inline("italic", italic = true),
                Inline(" with "),
                Inline("code", code = true),
                Inline(" and "),
                Inline("a link", url = "https://example.org"),
            ),
            Markdown.inlines("A **bold** and *italic* with `code` and [a link](https://example.org)"),
        )
    }

    @Test
    fun `unclosed markers stay literal until their partner arrives`() {
        assertEquals(listOf(Inline("half **bold")), Markdown.inlines("half **bold"))
        assertEquals(listOf(Inline("tick ` alone")), Markdown.inlines("tick ` alone"))
    }

    @Test
    fun `arithmetic and snake_case are not italics`() {
        assertEquals(listOf(Inline("3 * 4 = 12")), Markdown.inlines("3 * 4 = 12"))
        assertEquals(listOf(Inline("snake_case_name")), Markdown.inlines("snake_case_name"))
    }

    @Test
    fun `plain text drops the markup`() {
        assertEquals("Hola\n• uno\n• **dos**".replace("**", ""), Markdown.plainText("**Hola**\n- uno\n- **dos**"))
    }
}
