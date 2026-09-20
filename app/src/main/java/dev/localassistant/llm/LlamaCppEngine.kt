package dev.localassistant.llm

import android.util.Log
import dev.localassistant.tools.AssistantTool
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** GGUF models through llama.cpp on the CPU. */
class LlamaCppEngine private constructor(private var handle: Long) : LlmEngine {

    // GGUF models go through the plain chat template here; tool calling is LiteRT-LM only for now.
    override val supportsTools = false

    override fun generate(
        messages: List<ChatMessage>,
        tools: List<AssistantTool>,
        maxTokens: Int,
    ): Flow<GenerationEvent> = callbackFlow {
        check(handle != 0L) { "Engine is closed" }
        val stats = LongArray(7)
        val callback = object : ByteCallback {
            override fun onBytes(bytes: ByteArray): Boolean {
                if (!isActive) return false
                return trySendBlocking(GenerationEvent.Text(String(bytes, Charsets.UTF_8))).isSuccess && isActive
            }
        }
        val status = nativeGenerate(
            handle,
            messages.map { it.role.wire }.toTypedArray(),
            messages.map { it.text }.toTypedArray(),
            TEMPERATURE,
            maxTokens,
            callback,
            stats,
        )
        when (status) {
            STATUS_OK -> Log.i(TAG, "decode loop: sampling=${stats[5]}ms model=${stats[6]}ms total=${stats[4]}ms")
            else -> Unit
        }
        when (status) {
            STATUS_OK -> send(
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

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private interface ByteCallback {
        fun onBytes(bytes: ByteArray): Boolean
    }

    companion object {
        private const val TAG = "LlamaCppEngine"
        private const val TEMPERATURE = 0.7f
        private const val STATUS_OK = 0
        private const val STATUS_CONTEXT_OVERFLOW = 1

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
            roles: Array<String>,
            contents: Array<String>,
            temperature: Float,
            maxTokens: Int,
            callback: Any,
            stats: LongArray,
        ): Int
    }
}

class ContextOverflowException : IllegalStateException("Conversation does not fit in the context window")
