package dev.localassistant.ui.history

import dev.localassistant.history.ConversationSummary
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryGroupingTest {
    private val zone = TimeZone.getTimeZone("Europe/Madrid")
    private val now = Calendar.getInstance(zone).apply { set(2026, Calendar.SEPTEMBER, 25, 12, 0, 0) }.timeInMillis
    private fun hoursAgo(hours: Int) = now - hours * 3_600_000L
    private fun conversation(id: Long, at: Long) = ConversationSummary(id, at, "chat $id", 2)

    @Test
    fun `buckets by day, newest first`() {
        val groups = HistoryGrouping.group(
            listOf(
                conversation(1, hoursAgo(1)),
                conversation(2, hoursAgo(20)),
                conversation(3, hoursAgo(3 * 24)),
                conversation(4, hoursAgo(30 * 24)),
                conversation(5, hoursAgo(2)),
            ),
            now,
            zone,
        )
        assertEquals(listOf(DaySection.TODAY, DaySection.YESTERDAY, DaySection.THIS_WEEK, DaySection.EARLIER), groups.map { it.section })
        assertEquals(listOf(1L, 5L), groups[0].conversations.map { it.id })
    }

    @Test
    fun `filter matches titles ignoring case`() {
        val all = listOf(ConversationSummary(1, now, "Receta de paella", 4), ConversationSummary(2, now, "Tiempo", 2))
        assertEquals(listOf(1L), HistoryGrouping.filter(all, "PAELLA").map { it.id })
        assertEquals(2, HistoryGrouping.filter(all, "  ").size)
    }
}
