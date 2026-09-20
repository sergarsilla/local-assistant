package dev.localassistant.bench

import android.content.Context
import dev.localassistant.AssistantRuntime
import dev.localassistant.llm.Role
import java.io.File

/**
 * Checks that a conversation is stored, read back, and that deleting it removes the text from the
 * database file itself rather than just hiding the row.
 */
class HistoryTest(private val context: Context, private val report: (String) -> Unit) {

    fun run() {
        val store = AssistantRuntime.history
        val marker = "canario-${System.currentTimeMillis()}"
        val id = store.startConversation(marker)
        store.addMessage(id, Role.USER, marker)
        store.addMessage(id, Role.ASSISTANT, "respuesta de prueba $marker")

        val file = File(context.getDatabasePath("conversations.db").path)
        report("HISTORY stored_conversations=${store.conversations.value.size} messages=${store.messages(id).size}")
        report("HISTORY marker_in_file_before_delete=${contains(file, marker)} size=${file.length()}")

        store.delete(id)
        report("HISTORY remaining=${store.conversations.value.size} marker_in_file_after_delete=${contains(file, marker)}")
    }

    private fun contains(file: File, marker: String): Boolean {
        val needle = marker.toByteArray()
        val bytes = file.readBytes()
        outer@ for (start in 0..bytes.size - needle.size) {
            for (i in needle.indices) if (bytes[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }
}
