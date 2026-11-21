package dev.localassistant.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {
    @Test
    fun `blank and repeated lines are dropped and spacing tidied`() {
        assertEquals("Mamá\nTrae vino", ScreenText.join(listOf("  Mamá ", "Mamá", "", "Trae   vino")))
    }

    @Test
    fun `long screens are capped`() {
        assertTrue(ScreenText.join(List(5000) { "line $it" }).length <= ScreenText.MAX_CHARS)
    }
}
