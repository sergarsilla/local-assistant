package dev.localassistant.tools

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteSearchTest {
    @Test
    fun `amazon goes to the store of the user's country`() {
        assertEquals("https://www.amazon.es/s?k=funda+pixel+10", SiteSearch.url("amazon", "funda pixel 10", Locale("es", "ES")))
        assertEquals("https://www.amazon.co.uk/s?k=case", SiteSearch.url("amazon", "case", Locale.UK))
        assertEquals("https://www.amazon.com/s?k=case", SiteSearch.url("amazon", "case", Locale("en", "CA")))
    }

    @Test
    fun `other sites and unknown ones`() {
        assertEquals("https://www.youtube.com/results?search_query=hula+hoop", SiteSearch.url("youtube", "hula hoop", Locale.ROOT))
        assertEquals("https://es.wikipedia.org/w/index.php?search=Toledo", SiteSearch.url("wikipedia", "Toledo", Locale("es", "ES")))
        assertEquals("https://duckduckgo.com/?q=a%26b", SiteSearch.url("web", "a&b", Locale.ROOT))
        assertNull(SiteSearch.url("ebay", "x", Locale.ROOT))
    }
}
