package dev.localassistant.commands

import java.text.Normalizer
import kotlin.math.roundToInt

/** Helpers for matching what people say, in Spanish and English. */
internal object SpokenText {

    /**
     * Lowercase without accents, one character out per character in, so a position found in the
     * folded text still points at the same place in what the user actually said.
     */
    fun fold(text: String): String = buildString(text.length) {
        for (c in text) append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar())
    }

    /**
     * Rewrites number words as digits: "treinta y cinco" and "thirty-five" both become "35".
     * Speech recognisers spell numbers out about as often as not, and everything downstream is
     * far simpler when it only has to read digits.
     */
    fun digitize(folded: String): String {
        val out = ArrayList<String>()
        var thousands = 0
        var current = 0
        var active = false
        var last = Kind.NONE
        fun flush() {
            if (active) out += (thousands + current).toString()
            thousands = 0
            current = 0
            active = false
            last = Kind.NONE
        }

        val tokens = folded.replace(HYPHEN, " ").split(' ').filter { it.isNotEmpty() }
        for ((index, token) in tokens.withIndex()) {
            val word = token.trimEnd(*PUNCTUATION)
            val tail = token.substring(word.length)
            val next = tokens.getOrNull(index + 1)?.trimEnd(*PUNCTUATION)
            val small = SMALL[word]
            val tens = TENS_ES[word] ?: TENS_EN[word]
            val hundreds = HUNDREDS[word]
            when {
                small != null -> {
                    val afterTens = small in 1..9 && (last == Kind.TENS_EN || last == Kind.JOINED)
                    if (!(active && (afterTens || last == Kind.HUNDRED || last == Kind.THOUSAND))) flush()
                    current += small
                    active = true
                    last = Kind.SMALL
                }
                tens != null -> {
                    if (!(active && (last == Kind.HUNDRED || last == Kind.THOUSAND))) flush()
                    current += tens
                    active = true
                    last = if (word in TENS_EN) Kind.TENS_EN else Kind.TENS_ES
                }
                hundreds != null -> {
                    if (!(active && last == Kind.THOUSAND)) flush()
                    current += hundreds
                    active = true
                    last = Kind.HUNDRED
                }
                word == "hundred" && active && last == Kind.SMALL -> {
                    current *= 100
                    last = Kind.HUNDRED
                }
                word == "mil" || (word == "thousand" && active) -> {
                    thousands += maxOf(current, 1) * 1000
                    current = 0
                    active = true
                    last = Kind.THOUSAND
                }
                // "treinta y cinco" is one number, but "las siete y cinco" is five past seven.
                word == "y" && active && last == Kind.TENS_ES && (SMALL[next] in 1..9 || next in ONE_ES) ->
                    last = Kind.JOINED
                word == "and" && active && last == Kind.HUNDRED && (next in SMALL || next in TENS_EN) -> Unit
                word in ONE_ES && active && last == Kind.JOINED -> {
                    current += 1
                    last = Kind.SMALL
                }
                else -> {
                    flush()
                    out += token
                    continue
                }
            }
            if (tail.isNotEmpty()) {
                flush()
                out[out.lastIndex] = out.last() + tail
            }
        }
        flush()
        return out.joinToString(" ")
    }

    /** The total length of time mentioned in digitized text, in seconds, or null when there is none. */
    fun seconds(digitized: String): Int? {
        var text = " $digitized "
        for ((phrase, plain) in DURATION_PHRASES) text = text.replace(phrase, plain)
        var total = 0.0
        var found = false
        for (match in DURATION.findAll(text)) {
            val amount = (match.groups[1] ?: match.groups[3])!!.value
            val unit = (match.groups[2] ?: match.groups[4])!!.value
            val extra = match.groups[5]?.value.orEmpty()
            val count = (amount.replace(',', '.').toDoubleOrNull() ?: 1.0) + when {
                "cuarto" in extra -> 0.25
                extra.isNotEmpty() -> 0.5
                else -> 0.0
            }
            total += count * when (unit[0]) {
                'h' -> 3600
                'm' -> 60
                else -> 1
            }
            found = true
        }
        return total.roundToInt().takeIf { found && it > 0 }
    }

    /** "1 hora y 20 minutos" / "1 hour and 20 minutes". */
    fun spokenDuration(seconds: Int, spanish: Boolean): String {
        val parts = buildList {
            val hours = seconds / 3600
            val minutes = seconds % 3600 / 60
            val rest = seconds % 60
            if (hours > 0) add(plural(hours, if (spanish) "hora" else "hour"))
            if (minutes > 0) add(plural(minutes, if (spanish) "minuto" else "minute"))
            if (rest > 0) add(plural(rest, if (spanish) "segundo" else "second"))
        }
        val joiner = if (spanish) " y " else " and "
        return if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString(", ") + joiner + parts.last()
    }

    private fun plural(count: Int, unit: String) = if (count == 1) "1 $unit" else "$count ${unit}s"

    private enum class Kind { NONE, SMALL, TENS_ES, TENS_EN, JOINED, HUNDRED, THOUSAND }

    private val HYPHEN = Regex("(?<=[a-z])-(?=[a-z])")
    private val PUNCTUATION = charArrayOf('?', '!', '.', ',', ';', ':')
    private val ONE_ES = setOf("un", "una", "uno")

    private val SMALL: Map<String?, Int> = buildMap {
        listOf(
            "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
            "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete", "dieciocho", "diecinueve",
            "veinte", "veintiuno", "veintidos", "veintitres", "veinticuatro", "veinticinco", "veintiseis",
            "veintisiete", "veintiocho", "veintinueve",
        ).forEachIndexed { value, word -> put(word, value) }
        put("veintiun", 21)
        put("veintiuna", 21)
        listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        ).forEachIndexed { value, word -> put(word, value) }
    }
    private val TENS_ES = mapOf(
        "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60,
        "setenta" to 70, "ochenta" to 80, "noventa" to 90,
    )
    private val TENS_EN = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val HUNDREDS = mapOf(
        "cien" to 100, "ciento" to 100, "doscientos" to 200, "doscientas" to 200, "trescientos" to 300,
        "trescientas" to 300, "cuatrocientos" to 400, "cuatrocientas" to 400, "quinientos" to 500,
        "quinientas" to 500, "seiscientos" to 600, "seiscientas" to 600, "setecientos" to 700,
        "setecientas" to 700, "ochocientos" to 800, "ochocientas" to 800, "novecientos" to 900,
        "novecientas" to 900,
    )

    private val DURATION_PHRASES = listOf(
        " 3 cuartos de hora " to " 45 minutos ",
        " un cuarto de hora " to " 15 minutos ",
        " cuarto de hora " to " 15 minutos ",
        " media hora " to " 30 minutos ",
        " medio minuto " to " 30 segundos ",
        " a quarter of an hour " to " 15 minutes ",
        " quarter of an hour " to " 15 minutes ",
        " half an hour " to " 30 minutes ",
        " half hour " to " 30 minutes ",
        " half a minute " to " 30 seconds ",
    )

    // A bare letter only counts as a unit after digits: "a m" or "un s" are not lengths of time.
    private val DURATION = Regex(
        """(?:(\d+(?:[.,]\d+)?) ?(horas?|hours?|hrs?|h|minutos?|minutes?|mins?|m|segundos?|seconds?|segs?|secs?|s)""" +
            """|\b(una?|an?) (horas?|hours?|minutos?|minutes?|segundos?|seconds?))\b( y medi[ao]| and a half| y cuarto)?""",
    )
}
