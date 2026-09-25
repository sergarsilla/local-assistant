package dev.localassistant.keyboard

/** How dictated words join the text already in the field. */
object DictationFormatter {
    private const val SENTENCE_ENDS = ".!?¡¿\n"
    private const val TIGHT_PUNCTUATION = ",.;:!?)"

    /**
     * [before] is the text just before the cursor. Adds a space when the words would otherwise
     * touch the previous ones, and starts with a capital only at the start of a sentence: the
     * recognizer capitalises every utterance, which is wrong in the middle of one.
     */
    fun join(before: String, dictated: String): String {
        val text = dictated.trim()
        if (text.isEmpty()) return ""
        val needsSpace = before.isNotEmpty() && !before.last().isWhitespace() && text.first() !in TIGHT_PUNCTUATION
        val sentenceStart = before.isBlank() || before.trimEnd().lastOrNull()?.let { it in SENTENCE_ENDS } == true
        val cased = when {
            sentenceStart -> text.replaceFirstChar { it.uppercaseChar() }
            isAcronym(text) -> text
            else -> text.replaceFirstChar { it.lowercaseChar() }
        }
        return if (needsSpace) " $cased" else cased
    }

    /** "OK", "NASA": words written in capitals keep them. */
    private fun isAcronym(text: String): Boolean {
        val word = text.takeWhile { it.isLetter() }
        return word.length >= 2 && word.all { it.isUpperCase() }
    }
}
