package dev.localassistant.llm

import android.util.Log
import dev.localassistant.history.ConversationStore
import dev.localassistant.tools.AssistantTool
import dev.localassistant.tools.ToolRegistry
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A message as the panel shows it. [modelText] is what the model actually received, which can add
 * the current time or the text on screen to what the user typed or said; [image] is a screenshot
 * sent with it.
 */
data class UiMessage(
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
    val modelText: String = text,
    val image: ByteArray? = null,
    /** True when what was on screen went to the model with this message. */
    val withScreen: Boolean = false,
)

/** What was on screen, to send along with a question. */
class ScreenAttachment(val text: String?, val image: ByteArray?)

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
    /**
     * Returns the on-screen text to hand the model along with a question, or null. Set by the
     * assistant session, which knows what was on screen and whether the user asked about it.
     */
    @Volatile
    var screenFor: (question: String) -> ScreenAttachment? = { null }

    private var conversationId: Long? = null
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _error = MutableStateFlow<ChatError?>(null)
    val error: StateFlow<ChatError?> = _error.asStateFlow()

    private var job: Job? = null
    private var turn = 0

    /** Loading a saved conversation; a question asked meanwhile waits for it. */
    private var loading: Job? = null

    /**
     * Sends a question. If an answer is still being written it is stopped first, and the new
     * question waits for it to wind down instead of being dropped.
     */
    @Synchronized
    fun send(text: String, listener: ReplyListener? = null) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        val previous = job
        previous?.cancel()
        val myTurn = ++turn
        _generating.value = true
        val screen = screenFor(prompt)
        val question = UiMessage(
            role = Role.USER,
            text = prompt,
            modelText = decorate(prompt, screen),
            image = screen?.image,
            withScreen = screen != null,
        )
        val pendingLoad = loading
        job = scope.launch {
            previous?.cancelAndJoin()
            pendingLoad?.join()
            _error.value = null
            _messages.update { it + question + UiMessage(Role.ASSISTANT, "", streaming = true) }
            try {
                persist(Role.USER, prompt)
                holder.use { engine -> generateFitting(engine, listener) }
            } catch (e: NoModelException) {
                _error.value = ChatError.NoModel
                dropEmptyReply()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Generation failed", e)
                _error.value = ChatError.Failure(e.message ?: e.javaClass.simpleName)
                dropEmptyReply()
            } finally {
                listener?.onEnd()
                val reply = _messages.value.lastOrNull()?.takeIf { it.role == Role.ASSISTANT }?.text.orEmpty()
                _messages.update { list -> list.map { it.copy(streaming = false) } }
                // Whatever the assistant managed to say is kept, including a reply cut short.
                withContext(NonCancellable) { persist(Role.ASSISTANT, reply) }
                synchronized(this@ChatSession) { if (turn == myTurn) _generating.value = false }
            }
        }
    }

    /** The system message and tools every conversation starts with, for [EngineHolder.preload]. */
    fun preface(): Pair<List<ChatMessage>, List<AssistantTool>> =
        listOf(systemMessage()) to tools?.available().orEmpty()

    fun stop() {
        job?.cancel()
    }

    fun clear() {
        stop()
        _messages.value = emptyList()
        _error.value = null
        conversationId = null
    }

    /** Picks up a saved conversation where it was left; new turns are added to it. */
    @Synchronized
    fun resume(id: Long) {
        val store = history ?: return
        stop()
        loading = scope.launch {
            val saved = withContext(Dispatchers.IO) { store.messages(id) }
            _messages.value = saved
                .filter { it.role == Role.USER || it.role == Role.ASSISTANT }
                .map { UiMessage(it.role, it.text) }
            _error.value = null
            conversationId = id
        }
    }

    private suspend fun persist(role: Role, text: String) {
        val store = history ?: return
        if (text.isBlank() || !store.enabled.value) return
        runCatching {
            withContext(Dispatchers.IO) {
                synchronized(store) {
                    val id = conversationId ?: store.startConversation(text).also { conversationId = it }
                    store.addMessage(id, role, text)
                }
            }
        }.onFailure { Log.w(TAG, "Could not save the message", it) }
    }

    /** Drops the oldest turns until the conversation fits the context window. */
    private suspend fun generateFitting(engine: LlmEngine, listener: ReplyListener?) {
        // Only the latest question carries its screenshot: images are large to re-send and the
        // live conversation already holds the earlier ones.
        val shown = _messages.value.dropLast(1)
        val history = shown.mapIndexed { index, message ->
            val image = message.image?.takeIf { index == shown.lastIndex && engine.supportsImages }
            ChatMessage(message.role, message.modelText, image = image)
        }
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
     * conversation and are asked again, until they answer with text. The last round offers no
     * tools, so the model has to answer with what it has.
     */
    private suspend fun runWithTools(engine: LlmEngine, window: List<ChatMessage>, listener: ReplyListener?) {
        val available = if (engine.supportsTools) tools?.available().orEmpty() else emptyList()
        var conversation = window
        for (round in 0 until MAX_TOOL_ROUNDS) {
            val offered = if (round == MAX_TOOL_ROUNDS - 1) emptyList() else available
            val requested = mutableListOf<ToolCall>()
            var replyText = ""
            engine.generate(conversation, offered).collect { event ->
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
                val tool = offered.firstOrNull { it.name == call.name }
                val output = tool?.let {
                    runCatching { it.run(JSONObject(call.arguments)) }.getOrElse { error ->
                        JSONObject().put("error", error.message ?: "tool failed").toString()
                    }
                } ?: JSONObject().put("error", "unknown tool").toString()
                Log.i(TAG, "Tool ${call.name} -> ${output.take(120)}")
                ChatMessage(Role.TOOL, output, toolName = call.name)
            }
            conversation = conversation + ChatMessage(Role.ASSISTANT, replyText, toolCalls = requested) + results
        }
    }

    private fun appendToReply(chunk: String) = _messages.update { list ->
        val last = list.last()
        list.dropLast(1) + last.copy(text = (last.text + chunk).trimStart())
    }

    private fun dropEmptyReply() = _messages.update { list ->
        if (list.lastOrNull()?.let { it.role == Role.ASSISTANT && it.text.isEmpty() } == true) list.dropLast(1) else list
    }

    /**
     * The clock goes with each question rather than in the system prompt: the system prompt then
     * never changes, so both engines can keep it processed between questions.
     */
    private fun decorate(question: String, screen: ScreenAttachment?): String = buildString {
        val screenText = screen?.text
        append('[')
        append(SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date()))
        append(" ")
        append(TimeZone.getDefault().id)
        appendLine("]")
        if (screenText.isNullOrBlank() && screen?.image != null) {
            appendLine("(A screenshot of the user's screen is attached.)")
        }
        if (!screenText.isNullOrBlank()) {
            appendLine("Text on the user's screen:")
            appendLine("<<<")
            appendLine(screenText)
            appendLine(">>>")
        }
        append(question)
    }

    private fun systemMessage(): ChatMessage {
        val canSearch = tools?.webSearchEnabled() == true
        return ChatMessage(
            Role.SYSTEM,
            buildString {
                append("You are a voice and text assistant running entirely on the user's Android phone. ")
                append("Always reply in the language the user speaks or writes in (usually Spanish or English). ")
                append("Be brief and direct: one to three short sentences unless asked for more, because replies are often read aloud. ")
                append("Avoid tables and heavy markdown. ")
                append("Each question starts with the current date and time in brackets; use it for times and dates, never mention it otherwise. ")
                append("When the user asks you to do something on the phone, call the matching tool instead of explaining how to do it. ")
                append("After a tool runs, confirm in a few words what was done. ")
                if (canSearch) {
                    append("Use web_search only for recent or local facts you cannot know; otherwise answer from your own knowledge.")
                } else {
                    append("You have no internet access; if something needs up-to-date information, say so briefly.")
                }
            },
        )
    }

    private companion object {
        const val TAG = "ChatSession"
        const val MAX_TOOL_ROUNDS = 4
    }
}
