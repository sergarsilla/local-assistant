package dev.localassistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageGuessTest {
    @Test
    fun `spanish sentences`() {
        assertEquals("es", LanguageGuess.of("Claro, he puesto un temporizador de cinco minutos."))
        assertEquals("es", LanguageGuess.of("La reunión es mañana a las diez."))
        assertEquals("es", LanguageGuess.of("¿Algo más?"))
    }

    @Test
    fun `english sentences`() {
        assertEquals("en", LanguageGuess.of("Sure, I set a timer for five minutes."))
        assertEquals("en", LanguageGuess.of("The meeting is tomorrow at ten."))
    }

    @Test
    fun `too little to tell`() {
        assertNull(LanguageGuess.of("OK"))
        assertNull(LanguageGuess.of("Hola"))
    }
}
