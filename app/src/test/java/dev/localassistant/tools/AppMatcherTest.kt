package dev.localassistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMatcherTest {
    private val apps = listOf(
        "org.organicmaps" to "Organic Maps",
        "com.android.calculator2" to "Calculator",
        "com.google.android.apps.maps" to "Maps",
        "com.android.vending" to "Google Play Store",
        "app.grapheneos.camera" to "Camera",
    )

    @Test
    fun `exact names win`() {
        assertEquals("app.grapheneos.camera", AppMatcher.best("camera", apps)?.first)
    }

    @Test
    fun `spanish names match english labels through their stem`() {
        assertEquals("com.android.calculator2", AppMatcher.best("calculadora", apps)?.first)
    }

    @Test
    fun `fewer extra words break ties`() {
        assertEquals("com.google.android.apps.maps", AppMatcher.best("google maps", apps)?.first)
    }

    @Test
    fun `nothing plausible returns null`() {
        assertNull(AppMatcher.best("telegram", apps))
        assertNull(AppMatcher.best("", apps))
    }
}
