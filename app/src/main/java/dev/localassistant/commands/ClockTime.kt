package dev.localassistant.commands

import java.time.LocalDateTime

/** Reads a time of day out of digitized speech: "a las 7 y media", "at 6:45 pm", "quarter to 8". */
internal object ClockTime {

    /** Hour (0-23) and minute, or null when no time of day was said. */
    fun parse(digitized: String, now: LocalDateTime): Pair<Int, Int>? {
        val text = " $digitized ".replace(GLUED_PERIOD, "$1 $2")
        PAST_TO.find(text)?.let { match ->
            val hour = match.groupValues[2].toInt()
            return when (match.groupValues[1]) {
                "half past" -> resolve(hour, 30, period(text), now)
                "quarter past" -> resolve(hour, 15, period(text), now)
                else -> resolve(hour - 1, 45, period(text), now)
            }
        }
        val match = SPANISH.find(text) ?: ENGLISH.find(text) ?: BARE.find(text) ?: return null
        val hour = match.groupValues[1].toInt()
        var minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        var shift = 0
        val after = text.substring(match.range.last + 1)
        MINUTES_AFTER.find(after)?.let { extra ->
            val amount = when (extra.groupValues[2]) {
                "media" -> 30
                "cuarto" -> 15
                else -> extra.groupValues[2].toInt()
            }
            if (extra.groupValues[1] == "y") {
                minute = amount
            } else {
                minute = 60 - amount
                shift = -1
            }
        }
        return resolve(hour + shift, minute, period(text), now)
    }

    private enum class Period { MORNING, EVENING, NIGHT }

    private fun period(text: String): Period? = when {
        NIGHT.containsMatchIn(text) -> Period.NIGHT
        EVENING.containsMatchIn(text) -> Period.EVENING
        MORNING.containsMatchIn(text) -> Period.MORNING
        else -> null
    }

    private fun resolve(hour: Int, minute: Int, period: Period?, now: LocalDateTime): Pair<Int, Int>? {
        if (minute !in 0..59 || hour !in 0..24) return null
        if (hour > 12 || hour == 0) return hour % 24 to minute
        val morning = hour % 12
        return when (period) {
            Period.MORNING -> morning to minute
            Period.EVENING -> morning + 12 to minute
            // "Las 12 de la noche" is midnight, while "las 10 de la noche" is 22:00.
            Period.NIGHT -> (if (hour == 12) 0 else morning + 12) to minute
            // "A las 7" with nothing else means the next time the clock shows 7.
            null -> {
                val current = now.hour * 60 + now.minute
                listOf(morning, morning + 12).minBy { (it * 60 + minute - current - 1).mod(24 * 60) } to minute
            }
        }
    }

    private val SPANISH = Regex(""" (?:a|para|de) las? (\d{1,2})(?:(?:[:.h]| )(\d{2}))?\b""")
    private val ENGLISH = Regex(""" (?:at|for) (\d{1,2})(?:(?:[:.]| )(\d{2}))?\b""")
    private val BARE = Regex(""" (\d{1,2})[:.](\d{2})\b""")
    private val PAST_TO = Regex(""" (half past|quarter past|quarter to) (\d{1,2})\b""")
    private val MINUTES_AFTER = Regex("""^ (y|menos) (media|cuarto|\d{1,2})\b""")
    private val MORNING = Regex(""" (?:de la manana|de la madrugada|in the morning|am|a\.m\.?)[ ,.]""")
    private val EVENING = Regex(""" (?:de la tarde|del mediodia|in the afternoon|in the evening|pm|p\.m\.?)[ ,.]""")
    private val NIGHT = Regex(""" (?:de la noche|at night)[ ,.]""")
    private val GLUED_PERIOD = Regex("""(\d)([ap]\.?m)""")
}
