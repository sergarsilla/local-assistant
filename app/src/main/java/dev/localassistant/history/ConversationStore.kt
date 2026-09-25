package dev.localassistant.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.localassistant.llm.ChatMessage
import dev.localassistant.llm.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConversationSummary(
    val id: Long,
    val startedAt: Long,
    val title: String,
    val messageCount: Int,
)

/**
 * Conversations kept on the phone. Deleting one really removes it: `secure_delete` overwrites the
 * rows and `VACUUM` rewrites the file so nothing survives in free pages.
 */
class ConversationStore(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))

    /** When off, new conversations are not written anywhere; existing ones stay until deleted. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    private val helper = object : SQLiteOpenHelper(context, "conversations.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            // A PRAGMA returns a row, so execSQL refuses it; rawQuery is the way to set it.
            db.rawQuery("PRAGMA secure_delete = ON", null).close()
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE conversations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at INTEGER NOT NULL,
                    title TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    role TEXT NOT NULL,
                    text TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX messages_by_conversation ON messages(conversation_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    // Loaded on demand: opening the database must not slow down the assistant panel's first frame.
    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    fun startConversation(firstMessage: String): Long {
        val id = helper.writableDatabase.insert(
            "conversations",
            null,
            ContentValues().apply {
                put("started_at", System.currentTimeMillis())
                put("title", firstMessage.lineSequence().first().take(TITLE_CHARS))
            },
        )
        refresh()
        return id
    }

    fun addMessage(conversationId: Long, role: Role, text: String) {
        if (text.isBlank()) return
        helper.writableDatabase.insert(
            "messages",
            null,
            ContentValues().apply {
                put("conversation_id", conversationId)
                put("role", role.wire)
                put("text", text)
                put("created_at", System.currentTimeMillis())
            },
        )
        refresh()
    }

    fun messages(conversationId: Long): List<ChatMessage> =
        helper.readableDatabase.rawQuery(
            "SELECT role, text FROM messages WHERE conversation_id = ? ORDER BY id",
            arrayOf(conversationId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val role = Role.entries.firstOrNull { it.wire == cursor.getString(0) } ?: continue
                    add(ChatMessage(role, cursor.getString(1)))
                }
            }
        }

    fun delete(conversationId: Long) {
        helper.writableDatabase.delete("conversations", "id = ?", arrayOf(conversationId.toString()))
        compact()
        refresh()
    }

    fun deleteAll() {
        helper.writableDatabase.delete("conversations", null, null)
        compact()
        refresh()
    }

    /** Rewrites the database file so deleted text cannot be recovered from free pages. */
    private fun compact() = helper.writableDatabase.execSQL("VACUUM")

    fun refresh() {
        _conversations.value = load()
    }

    private fun load(): List<ConversationSummary> =
        helper.readableDatabase.rawQuery(
            """
            SELECT c.id, c.started_at, c.title, COUNT(m.id)
            FROM conversations c LEFT JOIN messages m ON m.conversation_id = c.id
            GROUP BY c.id ORDER BY c.started_at DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ConversationSummary(
                            id = cursor.getLong(0),
                            startedAt = cursor.getLong(1),
                            title = cursor.getString(2),
                            messageCount = cursor.getInt(3),
                        ),
                    )
                }
            }
        }

    private companion object {
        const val TITLE_CHARS = 80
        const val KEY_ENABLED = "enabled"
    }
}
