package dev.localassistant.ui.history

import dev.localassistant.history.ConversationSummary
import java.util.Calendar
import java.util.TimeZone

enum class DaySection { TODAY, YESTERDAY, THIS_WEEK, EARLIER }

data class HistoryGroup(val section: DaySection, val conversations: List<ConversationSummary>)

/** Buckets conversations by how long ago they started, newest first, as a chat app would. */
object HistoryGrouping {
    fun group(
        conversations: List<ConversationSummary>,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): List<HistoryGroup> {
        val today = startOfDay(now, zone)
        val yesterday = today - DAY_MS
        val weekAgo = today - 6 * DAY_MS
        return conversations
            .sortedByDescending { it.startedAt }
            .groupBy { conversation ->
                when {
                    conversation.startedAt >= today -> DaySection.TODAY
                    conversation.startedAt >= yesterday -> DaySection.YESTERDAY
                    conversation.startedAt >= weekAgo -> DaySection.THIS_WEEK
                    else -> DaySection.EARLIER
                }
            }
            .map { (section, items) -> HistoryGroup(section, items) }
            .sortedBy { it.section.ordinal }
    }

    fun filter(conversations: List<ConversationSummary>, query: String): List<ConversationSummary> {
        val needle = query.trim()
        if (needle.isEmpty()) return conversations
        return conversations.filter { it.title.contains(needle, ignoreCase = true) }
    }

    private fun startOfDay(time: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
