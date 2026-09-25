package dev.localassistant.models

import dev.localassistant.R

data class RemoteModelFile(val repo: String, val path: String, val sizeBytes: Long) {
    /** Repositories often call their weights "model.litertlm"; the repo name is what identifies it. */
    val fileName: String
        get() {
            val name = path.substringAfterLast('/')
            val stem = name.substringBeforeLast('.')
            return if (stem in GENERIC_NAMES) {
                repo.substringAfterLast('/') + "." + name.substringAfterLast('.')
            } else {
                name
            }
        }
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$path"

    private companion object {
        val GENERIC_NAMES = setOf("model", "weights", "gemma", "qwen")
    }
}

data class CatalogEntry(val title: String, val noteRes: Int, val file: RemoteModelFile)

/** Hand-picked models measured or known to work well in Spanish and English on phones. */
object Catalog {
    val recommended = listOf(
        CatalogEntry(
            "Gemma 4 E2B",
            R.string.catalog_note_gemma_e2b_litert,
            RemoteModelFile("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm", 2_588_147_712),
        ),
        CatalogEntry(
            "Gemma 4 E4B",
            R.string.catalog_note_gemma_e4b_litert,
            RemoteModelFile("litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm", 3_660_000_000),
        ),
        CatalogEntry(
            "Gemma 4 E2B · GGUF Q4_K_M",
            R.string.catalog_note_gemma_e2b_gguf,
            RemoteModelFile("unsloth/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_K_M.gguf", 3_110_000_000),
        ),
        CatalogEntry(
            "Qwen3 4B Instruct 2507 · GGUF Q4_K_M",
            R.string.catalog_note_qwen3_4b,
            RemoteModelFile("unsloth/Qwen3-4B-Instruct-2507-GGUF", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", 2_500_000_000),
        ),
    )

    /** What first-run setup downloads: fast, good in Spanish and English, and able to use tools. */
    val default: CatalogEntry get() = recommended.first()
}
