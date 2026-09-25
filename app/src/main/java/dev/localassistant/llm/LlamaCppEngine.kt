package dev.localassistant.llm

import android.util.Log
import dev.localassistant.tools.AssistantTool
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** GGUF models through llama.cpp on the CPU. */
class LlamaCppEngine private constructor(private var handle: Long) : LlmEngine {

    override val supportsTools = true

    /**
     * Decodes the system prompt and tool definitions into the KV cache. The chat is rendered with a
     * placeholder question and nothing is generated; the real first question then reuses everything
     * up to where the user's words start.
     */
    override suspend fun prepare(messages: List<ChatMessage>, tools: List<AssistantTool>) {
        withContext(Dispatchers.IO) {
            check(handle != 0L) { "Engine is closed" }
            val started = System.currentTimeMillis()
            val stats = LongArray(5)
            val status = nativeGenerate(
                handle,
                messagesJson(messages + ChatMessage(Role.USER, ".")),
                toolsJson(tools),
                TEMPERATURE,
                0,
                object : NativeCallback {
                    override fun onBytes(bytes: ByteArray) = false

                    override fun onToolCall(name: String, arguments: String) = Unit
                },
                stats,
            )
            Log.i(TAG, "Prepared ${stats[0]} prompt tokens in ${System.currentTimeMillis() - started}ms (status $status)")
        }
    }

    override fun generate(
        messages: List<ChatMessage>,
        tools: List<AssistantTool>,
        maxTokens: Int,
    ): Flow<GenerationEvent> = callbackFlow {
        check(handle != 0L) { "Engine is closed" }
        val stats = LongArray(5)
        val callback = object : NativeCallback {
            override fun onBytes(bytes: ByteArray): Boolean {
                if (!isActive) return false
                return trySendBlocking(GenerationEvent.Text(String(bytes, Charsets.UTF_8))).isSuccess && isActive
            }

            override fun onToolCall(name: String, arguments: String) {
                trySendBlocking(GenerationEvent.ToolRequest(ToolCall(name, arguments)))
            }
        }
        val status = nativeGenerate(
            handle,
            messagesJson(messages),
            toolsJson(tools),
            TEMPERATURE,
            maxTokens,
            callback,
            stats,
        )
        when (status) {
            STATUS_OK, STATUS_TOOL_CALLS -> send(
                GenerationEvent.Finished(
                    GenerationStats(
                        promptTokens = stats[0].toInt(),
                        prefillMillis = stats[2],
                        generatedTokens = stats[3].toInt(),
                        generateMillis = stats[4],
                    ),
                ),
            )
            STATUS_CONTEXT_OVERFLOW -> throw ContextOverflowException()
            else -> error("llama.cpp generation failed with status $status")
        }
        close()
        awaitClose()
    }.flowOn(Dispatchers.IO)

    /** The OpenAI-compatible shape llama.cpp's chat templates already understand. */
    private fun messagesJson(messages: List<ChatMessage>): String {
        val array = JSONArray()
        for (message in messages) {
            val entry = JSONObject().put("role", message.role.wire).put("content", message.text)
            message.toolName?.let { entry.put("name", it) }
            if (message.toolCalls.isNotEmpty()) {
                val calls = JSONArray()
                for (call in message.toolCalls) {
                    calls.put(
                        JSONObject()
                            .put("type", "function")
                            .put("id", call.name)
                            .put(
                                "function",
                                JSONObject().put("name", call.name).put("arguments", call.arguments),
                            ),
                    )
                }
                entry.put("tool_calls", calls)
            }
            array.put(entry)
        }
        return array.toString()
    }

    private fun toolsJson(tools: List<AssistantTool>): String {
        val array = JSONArray()
        for (tool in tools) {
            val described = JSONObject(tool.describe())
            array.put(JSONObject().put("type", "function").put("function", described))
        }
        return array.toString()
    }

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private interface NativeCallback {
        fun onBytes(bytes: ByteArray): Boolean

        fun onToolCall(name: String, arguments: String)
    }

    companion object {
        private const val TAG = "LlamaCppEngine"
        private const val TEMPERATURE = 0.7f
        private const val STATUS_OK = 0
        private const val STATUS_CONTEXT_OVERFLOW = 1
        private const val STATUS_TOOL_CALLS = 3

        init {
            System.loadLibrary("llama_jni")
        }

        fun load(model: File, contextSize: Int, threads: Int): LlamaCppEngine {
            val handle = nativeLoad(model.path, contextSize, threads)
            require(handle != 0L) { "llama.cpp could not load ${model.name}" }
            return LlamaCppEngine(handle)
        }

        @JvmStatic private external fun nativeLoad(path: String, contextSize: Int, threads: Int): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeGenerate(
            handle: Long,
            messagesJson: String,
            toolsJson: String,
            temperature: Float,
            maxTokens: Int,
            callback: Any,
            stats: LongArray,
        ): Int
    }
}

class ContextOverflowException : IllegalStateException("Conversation does not fit in the context window")
