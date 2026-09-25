package dev.localassistant.voice

/**
 * Tells Spanish from English in a sentence or two, so a reply is spoken with a voice of its own
 * language even when the model answers in the other one. Common function words are a reliable
 * signal at this length; anything undecided returns null and the caller keeps its default.
 */
object LanguageGuess {
    private val WORDS = Regex("[^\\p{L}]+")
    private val SPANISH = setOf(
        "el", "la", "los", "las", "un", "una", "de", "del", "que", "y", "en", "es", "por", "para",
        "con", "se", "lo", "su", "al", "como", "pero", "más", "muy", "está", "son", "hay",
        "tu", "te", "yo", "mi", "sí", "ya", "este", "esta", "puedes", "hoy", "mañana", "hora",
    )
    private val ENGLISH = setOf(
        "the", "an", "of", "and", "to", "in", "is", "for", "with", "not", "it", "you", "that",
        "this", "on", "are", "be", "at", "as", "your", "can", "i", "my", "was", "will", "have",
        "today", "tomorrow", "time", "what", "there", "here",
    )

    fun of(text: String): String? {
        if (text.any { it == '¿' || it == '¡' || it == 'ñ' || it == 'Ñ' }) return "es"
        var es = 0
        var en = 0
        for (word in text.lowercase().split(WORDS)) {
            if (word in SPANISH) es++
            if (word in ENGLISH) en++
        }
        return when {
            es >= en + 2 || (es > 0 && en == 0) -> "es"
            en >= es + 2 || (en > 0 && es == 0) -> "en"
            else -> null
        }
    }
}
