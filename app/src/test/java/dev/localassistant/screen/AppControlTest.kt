package dev.localassistant.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppControlTest {
    @Test
    fun `labels match whole words, best first`() {
        assertEquals(0, LabelMatch.score("Batería", "bateria"))
        assertTrue(LabelMatch.score("Battery saver", "battery") < LabelMatch.score("Adaptive battery", "battery"))
        assertTrue(LabelMatch.score("Adaptive battery", "battery") < LabelMatch.NONE)
        assertTrue(LabelMatch.score("Batteries", "battery") > LabelMatch.score("Adaptive battery", "battery"))
        assertEquals(LabelMatch.NONE, LabelMatch.score("Enviar", ""))
    }

    @Test
    fun `spanish words find their english labels`() {
        assertTrue(LabelMatch.score("Battery", "Batería") < LabelMatch.NONE)
        assertTrue(LabelMatch.score("Notifications", "notificaciones") < LabelMatch.NONE)
        assertTrue(LabelMatch.score("Security & privacy", "privacidad") < LabelMatch.NONE)
        assertTrue(LabelMatch.score("Battery", "battery") < LabelMatch.score("Battery", "bateria"))
        assertEquals(LabelMatch.NONE, LabelMatch.score("Display", "pantalla"))
        assertEquals(LabelMatch.NONE, LabelMatch.score("Apps", "ajustes"))
    }

    @Test
    fun `sending, deleting and paying need a confirmation`() {
        assertEquals(Consequence.Kind.SEND, Consequence.of("Enviar"))
        assertEquals(Consequence.Kind.SEND, Consequence.of("Send"))
        assertEquals(Consequence.Kind.DELETE, Consequence.of("Eliminar chat"))
        assertEquals(Consequence.Kind.DELETE, Consequence.of("Move to trash"))
        assertEquals(Consequence.Kind.PAY, Consequence.of("Comprar ahora"))
        assertEquals(Consequence.Kind.PAY, Consequence.of("Place order"))
    }

    @Test
    fun `everyday taps go straight through`() {
        assertNull(Consequence.of("Batería"))
        assertNull(Consequence.of("Lista de la compra"))
        assertNull(Consequence.of("Envío gratis"))
        assertNull(Consequence.of("Chats"))
        assertNull(Consequence.of("Buscar"))
    }
}
