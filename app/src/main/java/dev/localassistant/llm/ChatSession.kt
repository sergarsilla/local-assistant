package dev.localassistant.llm

import android.util.Log
import dev.localassistant.AssistantRuntime
import dev.localassistant.commands.Command
import dev.localassistant.commands.CommandRouter
import dev.localassistant.commands.SpokenText
import dev.localassistant.history.ConversationStore
import dev.localassistant.tools.AssistantTool
import dev.localassistant.tools.ToolRegistry
import org.json.JSONArray
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
    /** The language the user picked, so the model is told it rather than left to infer it. */
    private val language: () -> String = { AssistantRuntime.voiceSettings.state.value.language },
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

    /** The clock line given to the model this turn, so an echo of it never reaches the user. */
    private var clock = ""

    private val commands = CommandRouter()

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
                if (!answerDirectly(prompt, listener)) holder.use { engine -> generateFitting(engine, listener) }
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

    /**
     * Handles what needs no model: a recognised command runs its tool and is confirmed in a fixed
     * sentence, at once and even with no model installed. Returns false when the model should
     * answer after all, which includes a command whose tool refused, so that the model can ask
     * which of two contacts was meant or say what went wrong.
     */
    private suspend fun answerDirectly(prompt: String, listener: ReplyListener?): Boolean {
        val spanish = language() == "es"
        val reply = when (val command = commands.match(prompt, spanish)) {
            null -> return false
            is Command.Answer -> command.text
            is Command.Action -> {
                val tool = tools?.available()?.firstOrNull { it.name == command.tool } ?: return false
                val result = withContext(Dispatchers.IO) {
                    runCatching { JSONObject(tool.run(JSONObject(command.arguments))) }.getOrNull()
                }
                Log.i(TAG, "Command ${command.tool} -> ${result.toString().take(120)}")
                if (result == null || result.has("error")) return false
                command.confirmation
            }
            is Command.Lookup -> {
                val search = tools?.takeIf { it.webSearchEnabled() }?.available()?.firstOrNull { it.name == WEB_SEARCH }
                    ?: return say(
                        if (spanish) "La búsqueda en internet está desactivada. Actívala en Ajustes." else "Web search is turned off. Turn it on in Settings.",
                        listener,
                    )
                val results = withContext(Dispatchers.IO) {
                    runCatching { JSONObject(search.run(JSONObject().put("query", command.query))) }.getOrNull()
                }?.optJSONArray("results")?.takeIf { it.length() > 0 }
                    ?: return say(
                        if (spanish) "No he podido buscar en internet ahora mismo." else "I could not search the web just now.",
                        listener,
                    )
                // The results go in with the question, so the model only has to read them: it
                // is spared the decision to search, which is the step small models get wrong.
                _messages.update { list ->
                    val asked = list.indexOfLast { it.role == Role.USER }
                    list.mapIndexed { index, message ->
                        if (index == asked) message.copy(modelText = withResults(prompt, command.query, results)) else message
                    }
                }
                return false
            }
        }
        return say(reply, listener)
    }

    private fun say(reply: String, listener: ReplyListener?): Boolean {
        _messages.update { list -> list.dropLast(1) + list.last().copy(text = reply) }
        listener?.onChunk(reply)
        return true
    }

    private fun clockLine() = "[" + SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date()) +
        " " + TimeZone.getDefault().id + "]"

    private fun withResults(question: String, query: String, results: JSONArray): String = buildString {
        // What a search brings back is only "recent" against today's date.
        clock = clockLine()
        appendLine(clock)
        appendLine("Web search results for \"$query\":")
        appendLine("<<<")
        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            appendLine("${i + 1}. ${result.optString("title")}: ${result.optString("snippet")}")
        }
        appendLine(">>>")
        append("Answer from these results, in one or two sentences: ")
        append(question)
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
            var spoken = ""
            engine.generate(conversation, offered).collect { event ->
                when (event) {
                    is GenerationEvent.Text -> {
                        replyText += event.chunk
                        val shown = showReply(replyText)
                        if (shown.length > spoken.length) {
                            listener?.onChunk(shown.substring(spoken.length))
                            spoken = shown
                        }
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

    /**
     * Shows the reply so far and returns it. Small models often open by repeating the clock line
     * they were handed, so that echo is dropped, and hidden while it is still arriving.
     */
    private fun showReply(reply: String): String {
        // Some models think aloud without opening the block they then close, which the parser
        // cannot tell from an answer; what follows the last closing tag is the answer.
        val soFar = reply.substringAfterLast(THINK_END).trimStart()
        val text = when {
            soFar.startsWith(clock) -> soFar.removePrefix(clock).trimStart()
            clock.startsWith(soFar) -> ""
            else -> soFar
        }
        _messages.update { list -> list.dropLast(1) + list.last().copy(text = text) }
        return text
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
        // Handed the clock with every question, models work it into answers that have nothing
        // to do with it: a greeting came back with the full date and time read out.
        clock = if (TIME_WORDS.containsMatchIn(SpokenText.fold(question))) clockLine() else ""
        if (clock.isNotEmpty()) appendLine(clock)
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

    /**
     * What the model is told before every conversation. Written for a reply that is often heard
     * rather than read, in a car or over a shoulder, and against the two things these small
     * models do worst: padding an answer, and reaching for a tool nobody asked for.
     */
    private fun systemMessage(): ChatMessage {
        val canSearch = tools?.webSearchEnabled() == true
        return ChatMessage(
            Role.SYSTEM,
            buildString {
                append("You are a private assistant running entirely on the user's Android phone. ")
                // Naming the language beats asking a small model to infer it: Gemma 4 E2B kept
                // answering a Spanish question in English when told only to mirror the user.
                when (language()) {
                    "es" -> append("Responde siempre en español. ")
                    else -> append("Always reply in English. ")
                }

                append("Answer in one or two short sentences. Replies are usually read aloud, often while driving, ")
                append("so lead with the answer itself and leave out preamble, restating the question, and offers of ")
                append("further help. No tables, no headings, no bullet lists unless the user asks for a list. ")
                append("Spell out what will be spoken: no asterisks, no emoji, no URLs read character by character. ")

                append("Answer what was actually asked, and treat the user as an adult who owns this phone and ")
                append("means what they say. Do not lecture, warn, moralise, or add disclaimers. ")
                append("If you genuinely cannot do something, say so in one sentence and stop. ")
                append("If a request is ambiguous, make the most reasonable assumption and say which you made, ")
                append("rather than asking a question back. ")

                append("Tools do things on the phone. Call one only when the user asks for the thing it does: ")
                append("a question about facts, arithmetic, or how something works is never a tool call, however ")
                append("many numbers or times it mentions. When a tool fits, call it instead of explaining how the ")
                append("user could do it themselves, and afterwards confirm in a few words what happened. ")
                append("If a tool reports an error, say plainly what failed; never claim something was done when it was not. ")

                append("A question that depends on the date or the time begins with them in brackets; use them ")
                append("for the answer and never repeat them back. ")

                if (canSearch) {
                    append("Use web_search only for facts that change and that you cannot already know, such as news, ")
                    append("prices, or today's weather. Answer everything else from your own knowledge.")
                } else {
                    append("You have no internet access. If something needs current information, say so in one sentence.")
                }
            },
        )
    }

    private companion object {
        const val TAG = "ChatSession"
        const val MAX_TOOL_ROUNDS = 4
        const val WEB_SEARCH = "web_search"
        const val THINK_END = "</think>"
        val TIME_WORDS = Regex(
            """\b(?:hoy|manana|ayer|anoche|ahora|hora|horas|dia|dias|fecha|semana|mes|ano|cuando|tarde|noche|""" +
                """lunes|martes|miercoles|jueves|viernes|sabado|domingo|evento|cita|reunion|calendario|recuerdame|""" +
                """today|tomorrow|yesterday|tonight|now|time|date|day|days|week|month|year|when|morning|evening|""" +
                """monday|tuesday|wednesday|thursday|friday|saturday|sunday|event|meeting|calendar|remind)\b""",
        )
    }
}
