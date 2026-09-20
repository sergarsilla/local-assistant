package dev.localassistant.models

enum class ModelFormat(val extension: String) {
    GGUF(".gguf"),
    LITERT_LM(".litertlm");

    companion object {
        fun of(fileName: String): ModelFormat? = entries.firstOrNull { fileName.endsWith(it.extension, ignoreCase = true) }
    }
}
