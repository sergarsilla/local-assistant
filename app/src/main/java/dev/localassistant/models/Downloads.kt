package dev.localassistant.models

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

/** Progress of downloads and imports, observed by the models screen. */
object Transfers {
    private val _state = MutableStateFlow<Map<String, TransferState>>(emptyMap())
    val state: StateFlow<Map<String, TransferState>> = _state.asStateFlow()

    fun update(transfer: TransferState) = _state.update { it + (transfer.fileName to transfer) }

    fun clear(fileName: String) = _state.update { it - fileName }
}
