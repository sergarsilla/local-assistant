package dev.localassistant.models

import dev.localassistant.voice.VoicePack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface TransferState {
    val fileName: String

    data class Running(override val fileName: String, val doneBytes: Long, val totalBytes: Long) : TransferState {
        val fraction: Float get() = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
    }

    data class Failed(override val fileName: String, val message: String) : TransferState
}

/** Where a transfer comes from, so a failed one can be started again. */
sealed interface TransferSource {
    data class Model(val file: RemoteModelFile) : TransferSource
    data class Pack(val pack: VoicePack) : TransferSource
}

/** Progress of downloads and imports, observed by the screens. */
object Transfers {
    private val _state = MutableStateFlow<Map<String, TransferState>>(emptyMap())
    val state: StateFlow<Map<String, TransferState>> = _state.asStateFlow()

    private val sources = java.util.concurrent.ConcurrentHashMap<String, TransferSource>()

    fun update(transfer: TransferState) = _state.update { it + (transfer.fileName to transfer) }

    fun clear(fileName: String) {
        sources.remove(fileName)
        _state.update { it - fileName }
    }

    fun remember(fileName: String, source: TransferSource) {
        sources[fileName] = source
    }

    /** Null for imports, which need the user to pick the file again. */
    fun sourceOf(fileName: String): TransferSource? = sources[fileName]
}
