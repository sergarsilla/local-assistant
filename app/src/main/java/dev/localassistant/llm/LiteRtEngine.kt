package dev.localassistant.llm

import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import dev.localassistant.tools.AssistantTool
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** `.litertlm` models through LiteRT-LM, on the GPU when the phone allows it and on the CPU otherwise. */
class LiteRtEngine private constructor(
    private val engine: Engine,
    override val backendName: String,
) : LlmEngine {

    override val supportsTools = true

    private var conversation: Conversation? = null
    private var conversationTools: List<AssistantTool> = emptyList()

    /** Messages the live conversation already holds; null when it must be rebuilt. */
    private var conversationHistory: List<ChatMessage>? = null

    override suspend fun prepare(messages: List<ChatMessage>, tools: List<AssistantTool>) {
        withContext(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            conversationFor(messages, tools, prefill = true)
            Log.i(TAG, "Prepared the preface in ${SystemClock.elapsedRealtime() - started}ms")
        }
    }

    override fun generate(
        messages: List<ChatMessage>,
        tools: List<AssistantTool>,
        maxTokens: Int,
    ): Flow<GenerationEvent> = flow {
        val prior = messages.dropLast(1)
        val prompt = messages.last()
        require(prompt.role == Role.USER) { "The last message must come from the user" }
        val active = conversationFor(prior, tools, prefill = false)

        val started = SystemClock.elapsedRealtime()
        var firstChunkAt = 0L
        var chunks = 0
        val reply = StringBuilder()
        var completed = false
        // The callback API is used instead of the Flow one because only the callback says when a
        // cancelled turn has really stopped; closing the conversation before that crashes natively.
        val text = Channel<String>(Channel.UNLIMITED)
        val stopped = CompletableDeferred<Unit>()
        active.sendMessageAsync(
            Message.user(prompt.text),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    text.trySend(message.toString())
                }

                override fun onDone() {
                    text.close()
                    stopped.complete(Unit)
                }

                override fun onError(throwable: Throwable) {
                    text.close(throwable)
                    stopped.complete(Unit)
                }
            },
            maxOutputToken = maxTokens,
        )
        try {
            for (chunk in text) {
                if (chunk.isEmpty()) continue
                if (firstChunkAt == 0L) firstChunkAt = SystemClock.elapsedRealtime()
                chunks++
                reply.append(chunk)
                emit(GenerationEvent.Text(chunk))
            }
            completed = true
        } finally {
            if (completed) {
                conversationHistory = messages + ChatMessage(Role.ASSISTANT, reply.toString())
            } else {
                // LiteRT-LM cannot roll back a cancelled turn, so the next call rebuilds the conversation.
                conversationHistory = null
                runCatching { active.cancelProcess() }
                withContext(NonCancellable) {
                    if (withTimeoutOrNull(CANCEL_WAIT_MS) { stopped.await() } == null) {
                        Log.w(TAG, "The cancelled turn did not stop within ${CANCEL_WAIT_MS}ms")
                    }
                }
            }
        }
        val end = SystemClock.elapsedRealtime()
        emit(
            GenerationEvent.Finished(
                GenerationStats(
                    promptTokens = 0,
                    prefillMillis = if (firstChunkAt > 0) firstChunkAt - started else 0,
                    generatedTokens = chunks,
                    generateMillis = if (firstChunkAt > 0) end - firstChunkAt else 0,
                ),
            ),
        )
    }.flowOn(Dispatchers.IO)

    private fun conversationFor(prior: List<ChatMessage>, tools: List<AssistantTool>, prefill: Boolean): Conversation {
        conversation?.takeIf { conversationHistory == prior && conversationTools == tools }?.let { return it }
        conversation?.let { runCatching { it.close() } }
        conversation = null
        val system = prior.firstOrNull { it.role == Role.SYSTEM }?.text
        val initial = prior.filter { it.role == Role.USER || it.role == Role.ASSISTANT }.map {
            if (it.role == Role.USER) Message.user(it.text) else Message.model(it.text)
        }
        return engine.createConversation(
            ConversationConfig(
                systemInstruction = system?.let { Contents.of(it) },
                initialMessages = initial,
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                // Runs the system prompt and tool definitions through the model right away.
                prefillPrefaceOnInit = prefill,
                tools = tools.map { assistantTool ->
                    tool(
                        object : OpenApiTool {
                            override fun getToolDescriptionJsonString() = assistantTool.describe()

                            override fun execute(paramsJsonString: String) =
                                assistantTool.run(JSONObject(paramsJsonString))
                        },
                    )
                },
            ),
        ).also {
            conversation = it
            conversationHistory = prior
            conversationTools = tools
        }
    }

    override fun close() {
        conversation?.let { runCatching { it.close() } }
        conversation = null
        engine.close()
    }

    companion object {
        private const val TAG = "LiteRtEngine"
        private const val CANCEL_WAIT_MS = 3_000L

        /** Loads on [backend]; the caller decides what to fall back to when that fails. */
        fun load(model: File, cacheDir: File, backend: Backend, contextSize: Int): LiteRtEngine {
            val engine = Engine(
                EngineConfig(
                    modelPath = model.path,
                    backend = backend,
                    maxNumTokens = contextSize,
                    cacheDir = cacheDir.path,
                ),
            )
            try {
                engine.initialize()
            } catch (e: Throwable) {
                runCatching { engine.close() }
                throw e
            }
            return LiteRtEngine(engine, backend.name)
        }
    }
}
