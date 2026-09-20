package dev.localassistant.llm

import dev.localassistant.tools.AssistantTool
import kotlinx.coroutines.flow.Flow

enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

data class ToolCall(val name: String, val arguments: String)

data class ChatMessage(
    val role: Role,
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    /** Set on [Role.TOOL] messages: the tool that produced this result. */
    val toolName: String? = null,
)

data class GenerationStats(
    val promptTokens: Int,
    val prefillMillis: Long,
    val generatedTokens: Int,
    val generateMillis: Long,
) {
    val tokensPerSecond: Double
        get() = if (generateMillis > 0) generatedTokens * 1000.0 / generateMillis else 0.0
}

sealed interface GenerationEvent {
    data class Text(val chunk: String) : GenerationEvent

    /** The model asked for a tool; the caller runs it and continues the conversation. */
    data class ToolRequest(val call: ToolCall) : GenerationEvent
    data class Finished(val stats: GenerationStats?) : GenerationEvent
}

/** A loaded model. Implementations are not thread-safe: one generation at a time. */
interface LlmEngine : AutoCloseable {
    /** Models in formats without tool support answer from their own knowledge only. */
    val supportsTools: Boolean

    /** Streams the reply to the last user message; cancelling the collector stops generation. */
    fun generate(
        messages: List<ChatMessage>,
        tools: List<AssistantTool> = emptyList(),
        maxTokens: Int = 1024,
    ): Flow<GenerationEvent>
}
