package dev.localassistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatcherTest {
    private val contacts = listOf(
        Contact("Mamá", "600000001"),
        Contact("Ana García", "600000002"),
        Contact("Ana María López", "600000003"),
        Contact("Anabel", "600000004"),
        Contact("José Fontanero", "600000005"),
    )

    @Test
    fun `accents and case do not matter`() {
        assertEquals(listOf(contacts[0]), ContactMatcher.match("mama", contacts))
        assertEquals(listOf(contacts[0]), ContactMatcher.match("a mi mamá", contacts))
    }

    @Test
    fun `the closest name wins and ties are all returned`() {
        assertEquals(listOf(contacts[1]), ContactMatcher.match("Ana García", contacts))
        assertEquals(listOf(contacts[1]), ContactMatcher.match("ana", contacts))
    }

    @Test
    fun `partial words match by prefix`() {
        assertEquals(listOf(contacts[4]), ContactMatcher.match("el fontanero", contacts))
    }

    @Test
    fun `no match returns nothing`() {
        assertTrue(ContactMatcher.match("Pedro", contacts).isEmpty())
        assertTrue(ContactMatcher.match("", contacts).isEmpty())
    }

    @Test
    fun `numbers are recognised as numbers`() {
        assertTrue(ContactMatcher.looksLikeNumber("+34 600 11 22 33"))
        assertTrue(ContactMatcher.looksLikeNumber("600112233"))
        assertFalse(ContactMatcher.looksLikeNumber("Ana"))
        assertFalse(ContactMatcher.looksLikeNumber("12"))
    }
}
