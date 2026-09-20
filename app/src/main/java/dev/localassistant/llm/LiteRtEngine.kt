package dev.localassistant.llm

import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import dev.localassistant.tools.AssistantTool
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** `.litertlm` models through LiteRT-LM on the CPU. */
class LiteRtEngine private constructor(private val engine: Engine) : LlmEngine {

    override val supportsTools = true

    private var conversation: Conversation? = null
    private var conversationTools: List<AssistantTool> = emptyList()

    /** Messages the live conversation already holds; null when it must be rebuilt. */
    private var conversationHistory: List<ChatMessage>? = null

    override fun generate(
        messages: List<ChatMessage>,
        tools: List<AssistantTool>,
        maxTokens: Int,
    ): Flow<GenerationEvent> = flow {
        val prior = messages.dropLast(1)
        val prompt = messages.last()
        require(prompt.role == Role.USER) { "The last message must come from the user" }
        val active = conversationFor(prior, tools)

        val started = SystemClock.elapsedRealtime()
        var firstChunkAt = 0L
        var chunks = 0
        val reply = StringBuilder()
        var completed = false
        try {
            active.sendMessageAsync(Message.user(prompt.text), maxOutputToken = maxTokens).collect { message ->
                val text = message.toString()
                if (text.isEmpty()) return@collect
                if (firstChunkAt == 0L) firstChunkAt = SystemClock.elapsedRealtime()
                chunks++
                reply.append(text)
                emit(GenerationEvent.Text(text))
            }
            completed = true
        } finally {
            if (completed) {
                conversationHistory = messages + ChatMessage(Role.ASSISTANT, reply.toString())
            } else {
                // LiteRT-LM cannot roll back a cancelled turn, so the next call rebuilds the conversation.
                runCatching { active.cancelProcess() }
                conversationHistory = null
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

    private fun conversationFor(prior: List<ChatMessage>, tools: List<AssistantTool>): Conversation {
        conversation?.takeIf { conversationHistory == prior && conversationTools == tools }?.let { return it }
        conversation?.close()
        val system = prior.firstOrNull { it.role == Role.SYSTEM }?.text
        val initial = prior.filter { it.role != Role.SYSTEM }.map {
            if (it.role == Role.USER) Message.user(it.text) else Message.model(it.text)
        }
        return engine.createConversation(
            ConversationConfig(
                systemInstruction = system?.let { Contents.of(it) },
                initialMessages = initial,
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
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
        conversation?.close()
        conversation = null
        engine.close()
    }

    companion object {
        fun load(model: File, cacheDir: File, threads: Int, contextSize: Int): LiteRtEngine {
            val engine = Engine(
                EngineConfig(
                    modelPath = model.path,
                    backend = Backend.CPU(threadCount = threads),
                    maxNumTokens = contextSize,
                    cacheDir = cacheDir.path,
                ),
            )
            engine.initialize()
            return LiteRtEngine(engine)
        }
    }
}
