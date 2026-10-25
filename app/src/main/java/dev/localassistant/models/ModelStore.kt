package dev.localassistant.models

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class InstalledModel(
    val file: File,
    val format: ModelFormat,
    val source: String?,
    val tokensPerSecond: Double?,
    /** How much conversation this model is given to hold; null means the usual amount. */
    val contextTokens: Int?,
) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

/** Models live in app-private storage; per-model metadata and the active choice in preferences. */
class ModelStore(context: Context) {
    val directory: File = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    private val _activeName = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeName: StateFlow<String?> = _activeName.asStateFlow()

    /**
     * The model the assistant answers with. With none chosen, or after the chosen one is
     * deleted, it falls back to the quickest one that has been timed rather than whichever
     * sorts first: a phone this size holds models that differ tenfold in speed, and landing on
     * the slowest of them by alphabet is a poor first impression.
     */
    val active: InstalledModel?
        get() = installed.value.firstOrNull { it.name == activeName.value }
            ?: installed.value.maxByOrNull { it.tokensPerSecond ?: 0.0 }

    fun refresh() {
        _installed.value = scan()
    }

    fun setActive(name: String) {
        prefs.edit().putString(KEY_ACTIVE, name).apply()
        _activeName.value = name
    }

    fun recordSource(name: String, source: String) = updateMeta(name) { put("source", source) }

    fun recordSpeed(name: String, tokensPerSecond: Double) = updateMeta(name) { put("tps", tokensPerSecond) }

    fun setContext(name: String, tokens: Int) = updateMeta(name) { put("ctx", tokens) }

    fun delete(name: String) {
        File(directory, name).delete()
        prefs.edit().remove(metaKey(name)).apply()
        if (activeName.value == name) {
            prefs.edit().remove(KEY_ACTIVE).apply()
            _activeName.value = null
        }
        refresh()
    }

    private fun updateMeta(name: String, block: JSONObject.() -> Unit) {
        val meta = JSONObject(prefs.getString(metaKey(name), "{}") ?: "{}").apply(block)
        prefs.edit().putString(metaKey(name), meta.toString()).apply()
        refresh()
    }

    private fun scan(): List<InstalledModel> =
        directory.listFiles().orEmpty()
            .mapNotNull { file ->
                val format = ModelFormat.of(file.name) ?: return@mapNotNull null
                val meta = JSONObject(prefs.getString(metaKey(file.name), "{}") ?: "{}")
                InstalledModel(
                    file = file,
                    format = format,
                    source = meta.optString("source").ifEmpty { null },
                    tokensPerSecond = meta.optDouble("tps").takeUnless { it.isNaN() },
                    contextTokens = meta.optInt("ctx").takeIf { it > 0 },
                )
            }
            .sortedBy { it.name }

    private fun metaKey(name: String) = "meta:$name"

    private companion object {
        const val KEY_ACTIVE = "active"
    }
}
