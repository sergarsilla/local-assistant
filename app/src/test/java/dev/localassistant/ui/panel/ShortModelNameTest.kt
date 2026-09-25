package dev.localassistant.ui.panel

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortModelNameTest {
    @Test
    fun `drops extension, instruction tag and quantisation`() {
        assertEquals("gemma 4 E2B", shortModelName("gemma-4-E2B-it.litertlm"))
        assertEquals("Qwen3 4B 2507", shortModelName("Qwen3-4B-Instruct-2507-Q4_K_M.gguf"))
    }
}
