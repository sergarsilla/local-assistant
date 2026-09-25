package dev.localassistant.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTest {
    @Test
    fun `a download must leave the reserve free`() {
        val gb = 1_000_000_000L
        assertTrue(Storage.fits(2 * gb, 4 * gb))
        assertFalse(Storage.fits(3 * gb + 1, 4 * gb))
        assertTrue(Storage.fits(0, 0))
    }
}
