package dev.localassistant.voice

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable set of installed voice packs, refreshed after downloads and deletions. */
object VoicePacks {
    private lateinit var context: Context
    private val _installed = MutableStateFlow<Set<VoicePack>>(emptySet())
    val installed: StateFlow<Set<VoicePack>> = _installed.asStateFlow()

    fun init(context: Context) {
        this.context = context.applicationContext
        migrateKokoro()
        refresh()
    }

    /**
     * Older versions downloaded the Kokoro repository once per language into directories named
     * after each pack; the first complete copy becomes the shared one and the other is removed.
     */
    private fun migrateKokoro() {
        val shared = VoicePack.TTS_KOKORO_ES.directory(context)
        val legacy = listOf(VoicePack.TTS_KOKORO_ES, VoicePack.TTS_KOKORO_EN).map { File(context.filesDir, "voice/${it.id}") }
        for (dir in legacy.filter { it.exists() }) {
            val complete = File(dir, VoicePack.COMPLETE_MARKER).exists()
            if (complete && !File(shared, VoicePack.COMPLETE_MARKER).exists() && !shared.exists()) {
                dir.renameTo(shared)
            } else {
                dir.deleteRecursively()
            }
        }
    }

    fun refresh() {
        _installed.value = VoicePack.entries.filter { it.isInstalled(context) }.toSet()
    }

    /** Deleting a Kokoro voice removes the shared files, so both Kokoro voices go. */
    fun delete(pack: VoicePack) {
        pack.directory(context).deleteRecursively()
        refresh()
    }
}
