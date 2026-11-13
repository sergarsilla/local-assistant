package dev.localassistant.commands

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Spoken sums, worked out exactly. Small language models get "17 por 23" wrong more often than
 * right, and a wrong number said with confidence is worse than no answer.
 */
internal object Arithmetic {

    /** The value of [digitized] when all of it is one sum with at least one operation, else null. */
    fun evaluate(digitized: String): Double? {
        var text = " $digitized "
        for ((phrase, symbol) in PHRASES) text = text.replace(phrase, symbol)
        text = text.replace(TIMES_SIGN, "*").replace(DECIMAL_COMMA, ".")
        val tokens = TOKEN.findAll(text).map { it.value }.toList()
        // Anything the tokens do not account for means this was not just a sum.
        if (tokens.joinToString("") != text.filterNot { it == ' ' }) return null
        if (tokens.none { it in OPERATIONS }) return null
        return runCatching { Parser(tokens).parse() }.getOrNull()?.takeIf { it.isFinite() }
    }

    /** "391", "3,5" in Spanish and "3.5" in English; at most four decimals. */
    fun format(value: Double, spanish: Boolean): String {
        if (abs(value - Math.rint(value)) < 1e-9 && abs(value) < 1e15) return Math.rint(value).toLong().toString()
        val plain = BigDecimal(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (spanish) plain.replace('.', ',') else plain
    }

    private class Parser(private val tokens: List<String>) {
        private var position = 0

        fun parse(): Double = sum().also { check(position == tokens.size) }

        private fun peek() = tokens.getOrNull(position)
        private fun take() = tokens[position++]

        private fun sum(): Double {
            var value = product()
            while (peek() == "+" || peek() == "-") value = if (take() == "+") value + product() else value - product()
            return value
        }

        private fun product(): Double {
            var value = power()
            while (peek() == "*" || peek() == "/") {
                if (take() == "*") {
                    value *= power()
                } else {
                    val divisor = power()
                    check(divisor != 0.0)
                    value /= divisor
                }
            }
            return value
        }

        private fun power(): Double {
            val base = unary()
            return if (peek() == "^") {
                take()
                base.pow(unary())
            } else {
                base
            }
        }

        private fun unary(): Double = when (peek()) {
            "-" -> {
                take()
                -unary()
            }
            "sqrt" -> {
                take()
                sqrt(unary())
            }
            "(" -> {
                take()
                sum().also { check(take() == ")") }
            }
            else -> {
                val number = take().toDouble()
                if (peek() == "%of") {
                    take()
                    number / 100 * unary()
                } else {
                    number
                }
            }
        }
    }

    private val OPERATIONS = setOf("+", "-", "*", "/", "^", "sqrt", "%of")
    private val TOKEN = Regex("""\d+(?:\.\d+)?|%of|sqrt|[-+*/^()]""")
    private val TIMES_SIGN = Regex("""(?<=\d) ?[x×] ?(?=\d)""")
    private val DECIMAL_COMMA = Regex("""(?<=\d),(?=\d)""")

    // Longer phrases first: "por ciento de" must not be read as a multiplication.
    private val PHRASES = listOf(
        " la raiz cuadrada de " to " sqrt ",
        " raiz cuadrada de " to " sqrt ",
        " the square root of " to " sqrt ",
        " square root of " to " sqrt ",
        // By the time a sum gets here "ciento" has already been read as the number it also is.
        " por 100 de " to " %of ",
        " por ciento de " to " %of ",
        " por cien de " to " %of ",
        " percent of " to " %of ",
        "% de " to " %of ",
        "% of " to " %of ",
        " multiplicado por " to " * ",
        " multiplied by " to " * ",
        " dividido entre " to " / ",
        " dividido por " to " / ",
        " divided by " to " / ",
        " elevado a la " to " ^ ",
        " elevado a " to " ^ ",
        " to the power of " to " ^ ",
        " al cuadrado " to " ^ 2 ",
        " squared " to " ^ 2 ",
        " al cubo " to " ^ 3 ",
        " cubed " to " ^ 3 ",
        " por " to " * ",
        " times " to " * ",
        " entre " to " / ",
        " over " to " / ",
        " mas " to " + ",
        " plus " to " + ",
        " menos " to " - ",
        " minus " to " - ",
        " el " to " ",
    )
}
