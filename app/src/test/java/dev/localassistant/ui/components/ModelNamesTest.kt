package dev.localassistant.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelNamesTest {
    @Test
    fun `drops extension, instruction tag and quantisation, and capitalises`() {
        assertEquals("Gemma 4 E2B", displayModelName("gemma-4-E2B-it.litertlm"))
        assertEquals("Qwen3 4B 2507", displayModelName("Qwen3-4B-Instruct-2507-Q4_K_M.gguf"))
    }
}
