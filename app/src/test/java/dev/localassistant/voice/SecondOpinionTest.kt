package dev.localassistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecondOpinionTest {
    private val all: (VoicePack) -> Boolean = { true }

    @Test
    fun `english heard while speaking spanish gets a second go`() {
        assertEquals(
            VoicePack.STT_CANARY,
            SecondOpinion.pack(VoicePack.STT_PARAKEET, "es", "I want to know what time it is", all),
        )
    }

    @Test
    fun `a transcript in the expected language is kept`() {
        assertNull(SecondOpinion.pack(VoicePack.STT_PARAKEET, "es", "pon una alarma a las siete", all))
        assertNull(SecondOpinion.pack(VoicePack.STT_PARAKEET, "en", "what time is it", all))
    }

    @Test
    fun `undecided or empty transcripts are kept`() {
        assertNull(SecondOpinion.pack(VoicePack.STT_PARAKEET, "es", "Spotify", all))
        assertNull(SecondOpinion.pack(VoicePack.STT_PARAKEET, "es", "", all))
    }

    @Test
    fun `nothing to retry with when canary is missing or already used`() {
        assertNull(SecondOpinion.pack(VoicePack.STT_PARAKEET, "es", "I want to know what time it is") { false })
        assertNull(SecondOpinion.pack(VoicePack.STT_CANARY, "es", "I want to know what time it is", all))
    }
}
