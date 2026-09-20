package dev.localassistant.voice

import android.content.Context
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
        refresh()
    }

    fun refresh() {
        _installed.value = VoicePack.entries.filter { it.isInstalled(context) }.toSet()
    }

    fun delete(pack: VoicePack) {
        pack.directory(context).deleteRecursively()
        refresh()
    }
}
