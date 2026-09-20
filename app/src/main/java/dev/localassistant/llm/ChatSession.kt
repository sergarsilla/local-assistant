package dev.localassistant.llm

import android.util.Log
import dev.localassistant.history.ConversationStore
import dev.localassistant.tools.ToolRegistry
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiMessage(val role: Role, val text: String, val streaming: Boolean = false)

/** Receives the reply as it streams, e.g. to speak it aloud. */
interface ReplyListener {
    fun onChunk(text: String)
    fun onEnd()
}

sealed interface ChatError {
    data object NoModel : ChatError
    data class Failure(val message: String) : ChatError
}

/** One conversation: history, streaming of the current reply, and interruption. */
class ChatSession(
    private val holder: EngineHolder,
    private val scope: CoroutineScope,
    private val tools: ToolRegistry? = null,
    private val history: ConversationStore? = null,
) {
    private var conversationId: Long? = null
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _error = MutableStateFlow<ChatError?>(null)
    val error: StateFlow<ChatError?> = _error.asStateFlow()

    private var job: Job? = null

    fun send(text: String, listener: ReplyListener? = null) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _generating.value) return
        _error.value = null
        _messages.update { it + UiMessage(Role.USER, prompt) + UiMessage(Role.ASSISTANT, "", streaming = true) }
        history?.let { store ->
            val id = conversationId ?: store.startConversation(prompt).also { conversationId = it }
            store.addMessage(id, Role.USER, prompt)
        }
        _generating.value = true
        job = scope.launch {
            try {
                val engine = holder.acquire()
                generateFitting(engine, listener)
            } catch (e: NoModelException) {
                _error.value = ChatError.NoModel
                dropEmptyReply()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _error.value = ChatError.Failure(e.message ?: e.javaClass.simpleName)
                dropEmptyReply()
            } finally {
                listener?.onEnd()
                persistReply()
                _messages.update { list -> list.map { it.copy(streaming = false) } }
                _generating.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clear() {
        stop()
        _messages.value = emptyList()
        _error.value = null
        conversationId = null
    }

    /** Stores whatever the assistant managed to say, including a reply cut short. */
    private fun persistReply() {
        val store = history ?: return
        val id = conversationId ?: return
        val reply = _messages.value.lastOrNull()?.takeIf { it.role == Role.ASSISTANT }?.text.orEmpty()
        store.addMessage(id, Role.ASSISTANT, reply)
    }

    /** Drops the oldest turns until the conversation fits the context window. */
    private suspend fun generateFitting(engine: LlmEngine, listener: ReplyListener?) {
        val history = _messages.value.dropLast(1).map { ChatMessage(it.role, it.text) }
        var skip = 0
        while (true) {
            val window = listOf(systemMessage()) + history.drop(skip)
            try {
                runWithTools(engine, window, listener)
                return
            } catch (e: ContextOverflowException) {
                if (history.size - skip <= 1) throw e
                skip += 2
            }
        }
    }

    /**
     * Engines that report tool calls instead of running them get the results appended to the
     * conversation and are asked again, until they answer with text or the budget runs out.
     */
    private suspend fun runWithTools(engine: LlmEngine, window: List<ChatMessage>, listener: ReplyListener?) {
        val available = if (engine.supportsTools) tools?.available().orEmpty() else emptyList()
        var conversation = window
        repeat(MAX_TOOL_ROUNDS) {
            val requested = mutableListOf<ToolCall>()
            var replyText = ""
            engine.generate(conversation, available).collect { event ->
                when (event) {
                    is GenerationEvent.Text -> {
                        replyText += event.chunk
                        appendToReply(event.chunk)
                        listener?.onChunk(event.chunk)
                    }
                    is GenerationEvent.ToolRequest -> requested += event.call
                    is GenerationEvent.Finished -> event.stats?.let {
                        Log.i(
                            TAG,
                            "Reply: prompt=${it.promptTokens} tok, prefill=${it.prefillMillis}ms, " +
                                "generated=${it.generatedTokens} tok in ${it.generateMillis}ms " +
                                "(%.1f tok/s)".format(it.tokensPerSecond),
                        )
                        holder.recordSpeed(it)
                    }
                }
            }
            if (requested.isEmpty()) return

            val results = requested.map { call ->
                val tool = available.firstOrNull { it.name == call.name }
                val output = tool?.let { runCatching { it.run(JSONObject(call.arguments)) }.getOrElse { error -> 
                    JSONObject().put("error", error.message ?: "tool failed").toString()
                } } ?: JSONObject().put("error", "unknown tool").toString()
                Log.i(TAG, "Tool ${call.name} -> ${output.take(120)}")
                ChatMessage(Role.TOOL, output, toolName = call.name)
            }
            conversation = conversation + ChatMessage(Role.ASSISTANT, replyText, toolCalls = requested) + results
        }
        Log.w(TAG, "Gave up after $MAX_TOOL_ROUNDS tool rounds")
    }

    private fun appendToReply(chunk: String) = _messages.update { list ->
        val last = list.last()
        list.dropLast(1) + last.copy(text = (last.text + chunk).trimStart())
    }

    private fun dropEmptyReply() = _messages.update { list ->
        if (list.lastOrNull()?.let { it.role == Role.ASSISTANT && it.text.isEmpty() } == true) list.dropLast(1) else list
    }

    private companion object {
        const val TAG = "ChatSession"
        const val MAX_TOOL_ROUNDS = 4
    }

    private fun systemMessage() = ChatMessage(
        Role.SYSTEM,
        "You are a helpful assistant running entirely on the user's phone, without internet access. " +
            "Always reply in the language the user writes in. Be concise and direct; use short paragraphs " +
            "or lists only when they help. Today is " +
            DateFormat.getDateInstance(DateFormat.FULL).format(Date()) + ".",
    )
}
