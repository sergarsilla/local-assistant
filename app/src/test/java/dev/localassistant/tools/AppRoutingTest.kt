package dev.localassistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppRoutingTest {
    private val osmand = "net.osmand.plus"
    private val organic = "app.organicmaps"

    /** A phone where every installed maps app shows places and only some of them navigate. */
    private fun phone(installed: Set<String>, navigators: Set<String> = emptySet()): (MapsRoute) -> Boolean = { route ->
        when (route.scheme) {
            MapsScheme.NAVIGATION -> route.packageName in navigators
            MapsScheme.GEO -> if (route.packageName == null) installed.isNotEmpty() else route.packageName in installed
        }
    }

    @Test
    fun `navigation goes to the chosen app when it understands it`() {
        val route = AppRouting.maps(navigate = true, osmand, phone(setOf(osmand), navigators = setOf(osmand)))
        assertEquals(MapsRoute(MapsScheme.NAVIGATION, osmand), route)
    }

    @Test
    fun `a chosen app that cannot navigate still gets the place`() {
        val route = AppRouting.maps(navigate = true, organic, phone(setOf(organic, osmand), navigators = setOf(osmand)))
        assertEquals(MapsRoute(MapsScheme.GEO, organic), route)
    }

    @Test
    fun `showing a place never asks for navigation`() {
        val route = AppRouting.maps(navigate = false, osmand, phone(setOf(osmand), navigators = setOf(osmand)))
        assertEquals(MapsRoute(MapsScheme.GEO, osmand), route)
    }

    @Test
    fun `an uninstalled choice falls back to whatever Android offers`() {
        val route = AppRouting.maps(navigate = true, osmand, phone(setOf(organic)))
        assertEquals(MapsRoute(MapsScheme.GEO, null), route)
    }

    @Test
    fun `without a choice no app is preferred, even one that navigates`() {
        val google = "com.google.android.apps.maps"
        val route = AppRouting.maps(navigate = true, null, phone(setOf(google, organic), navigators = setOf(google)))
        assertEquals(MapsRoute(MapsScheme.GEO, null), route)
    }

    @Test
    fun `no maps app at all returns null`() {
        assertNull(AppRouting.maps(navigate = true, osmand, phone(emptySet())))
        assertNull(AppRouting.maps(navigate = true, null, phone(emptySet())))
    }

    @Test
    fun `maps links carry the query and the travel mode`() {
        assertEquals("google.navigation:q=Plaza%20Mayor&mode=w", AppRouting.mapsUri(MapsScheme.NAVIGATION, "Plaza%20Mayor", "w"))
        assertEquals("geo:0,0?q=Plaza%20Mayor", AppRouting.mapsUri(MapsScheme.GEO, "Plaza%20Mayor", "w"))
    }

    @Test
    fun `sms stays the route unless whatsapp is both chosen and installed`() {
        assertEquals(MessageRoute.Sms, AppRouting.message(MessagingApp.SMS, whatsAppInstalled = true, "+34 600 11 22 33"))
        assertEquals(MessageRoute.Sms, AppRouting.message(MessagingApp.WHATSAPP, whatsAppInstalled = false, "+34 600 11 22 33"))
    }

    @Test
    fun `an international number opens its whatsapp chat`() {
        val route = AppRouting.message(MessagingApp.WHATSAPP, whatsAppInstalled = true, " +34 600-11 (22) 33")
        assertEquals(MessageRoute.WhatsAppChat("34600112233"), route)
        assertEquals("https://wa.me/34600112233?text=Llego%20tarde", AppRouting.whatsAppChatUrl("34600112233", "Llego%20tarde"))
    }

    @Test
    fun `a number without country code lets whatsapp ask for the recipient`() {
        assertEquals(MessageRoute.WhatsAppShare, AppRouting.message(MessagingApp.WHATSAPP, whatsAppInstalled = true, "600112233"))
        assertEquals(MessageRoute.WhatsAppShare, AppRouting.message(MessagingApp.WHATSAPP, whatsAppInstalled = true, ""))
        assertEquals(MessageRoute.WhatsAppShare, AppRouting.message(MessagingApp.WHATSAPP, whatsAppInstalled = true, "+"))
    }
}
