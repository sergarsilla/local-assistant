package dev.localassistant.ui.components

/**
 * A readable name for a model file: "gemma-4-E2B-it.litertlm" becomes "Gemma 4 E2B". The
 * extension, instruction-tuning tags and quantisation suffixes mean little to a person.
 */
fun displayModelName(fileName: String): String = fileName
    .substringBeforeLast('.')
    .replace(Regex("[-_](it|instruct)(?=[-_.]|$)", RegexOption.IGNORE_CASE), "")
    .replace(Regex("[-_](Q\\d\\w*|q\\d\\w*|int\\d|f16|bf16)$"), "")
    .replace('-', ' ')
    .replace('_', ' ')
    .replaceFirstChar { it.uppercaseChar() }
