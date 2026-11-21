package dev.localassistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTrimTest {
    private val u = Role.USER
    private val a = Role.ASSISTANT

    @Test
    fun `half of a long chat is dropped at once`() {
        val roles = List(10) { if (it % 2 == 0) u else a } + u
        assertEquals(6, ContextTrim.nextSkip(roles, 0))
        assertEquals(8, ContextTrim.nextSkip(roles, 6))
    }

    @Test
    fun `the kept part starts at a question`() {
        assertEquals(2, ContextTrim.nextSkip(listOf(u, a, u, a, u), 0))
        assertEquals(4, ContextTrim.nextSkip(listOf(u, u, a, a, u, a, u), 0))
    }

    @Test
    fun `unanswered questions in a row are dropped too and the latest is kept`() {
        val roles = listOf(u, a, u, u, u, u)
        assertEquals(3, ContextTrim.nextSkip(roles, 0))
        assertEquals(5, ContextTrim.nextSkip(roles, 4))
    }

    @Test
    fun `later turns start where the last trim left off`() {
        assertEquals(0, ContextTrim.startAt(listOf(u, a, u), 0))
        assertEquals(2, ContextTrim.startAt(listOf(u, a, u, a, u), 1))
        assertEquals(2, ContextTrim.startAt(listOf(u, a, u), 9))
    }

    @Test
    fun `liteRT overflow errors are recognised`() {
        assertTrue(
            LiteRtEngine.isContextOverflow(
                "INVALID_ARGUMENT: Input token ids are too long. Exceeding the maximum number of tokens allowed: 4100 >= 4096",
            ),
        )
        assertTrue(
            LiteRtEngine.isContextOverflow(
                "Status Code: 9. Message: Prefill input length exceeds available state entries (remaining capacity: 193).",
            ),
        )
        assertTrue(LiteRtEngine.isContextOverflow("Max number of tokens reached, context window out of bounds"))
        assertFalse(LiteRtEngine.isContextOverflow("Failed to load model"))
        assertFalse(LiteRtEngine.isContextOverflow(null))
    }
}
