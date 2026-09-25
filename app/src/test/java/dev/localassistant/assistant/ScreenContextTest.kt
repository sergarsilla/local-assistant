package dev.localassistant.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContextTest {
    @Test
    fun `questions about the screen are recognised in both languages`() {
        listOf("resúmelo", "¿qué pone en la pantalla?", "tradúceme el mensaje", "summarize this page", "what does this say")
            .forEach { assertTrue(it, ScreenContext.mentionsScreen(it)) }
    }

    @Test
    fun `other questions are not`() {
        listOf("pon una alarma a las 7", "set a timer for 5 minutes", "¿qué tiempo hará mañana?")
            .forEach { assertFalse(it, ScreenContext.mentionsScreen(it)) }
    }
}
