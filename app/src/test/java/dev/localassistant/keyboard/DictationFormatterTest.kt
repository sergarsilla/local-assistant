package dev.localassistant.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationFormatterTest {
    @Test
    fun `an empty field starts a sentence`() {
        assertEquals("Hola qué tal.", DictationFormatter.join("", "hola qué tal."))
    }

    @Test
    fun `mid sentence it adds a space and drops the capital`() {
        assertEquals(" y luego voy.", DictationFormatter.join("Compro pan", "Y luego voy."))
    }

    @Test
    fun `after a full stop it capitalises`() {
        assertEquals(" Mañana te llamo.", DictationFormatter.join("Vale.", "mañana te llamo."))
        assertEquals("Mañana te llamo.", DictationFormatter.join("Vale. ", "mañana te llamo."))
    }

    @Test
    fun `acronyms keep their capitals and punctuation sticks to the word`() {
        assertEquals(" OK, vamos.", DictationFormatter.join("Te digo", "OK, vamos."))
        assertEquals(",", DictationFormatter.join("Hola", ","))
    }
}
