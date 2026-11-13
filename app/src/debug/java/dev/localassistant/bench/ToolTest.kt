package dev.localassistant.bench

import android.content.Context
import android.os.SystemClock
import dev.localassistant.AssistantRuntime
import dev.localassistant.llm.ChatSession
import kotlinx.coroutines.runBlocking

/** Sends one question through the real chat session with tools enabled and reports what happened. */
class ToolTest(private val context: Context, private val report: (String) -> Unit) {

    /** With [preload] the preface is prepared first, as the panel does the moment it opens. */
    fun run(question: String, preload: Boolean = false) = runBlocking {
        val chat = ChatSession(
            holder = AssistantRuntime.engine,
            scope = AssistantRuntime.scope,
            tools = AssistantRuntime.tools,
            history = AssistantRuntime.history,
        )
        // The registry keeps the last tool it ran, so without this every question inherits the
        // previous one's tool and a model that called nothing looks like it called maps.
        AssistantRuntime.tools.clearActivity()
        val start = SystemClock.elapsedRealtime()
        var seenTool: String? = null
        if (preload) {
            AssistantRuntime.engine.preload(chat::preface)
            // Lets the preload take the engine first, so the question queues behind it.
            SystemClock.sleep(300)
        }
        chat.send(question)
        while (chat.generating.value && SystemClock.elapsedRealtime() - start < 180_000) {
            AssistantRuntime.tools.activity.value?.let { seenTool = "${it.name}(${it.summary})" }
            SystemClock.sleep(100)
        }
        report("TOOL question='$question' took=${SystemClock.elapsedRealtime() - start}ms tool=$seenTool")
        report("TOOL pending_action=${AssistantRuntime.tools.actions.pending.value?.detail}")
        report("TOOL reply='${chat.messages.value.lastOrNull()?.text?.replace('\n', ' ')?.take(300)}'")
        AssistantRuntime.tools.actions.clear()
    }
}
