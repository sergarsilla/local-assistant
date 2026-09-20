package dev.localassistant.bench

import android.content.Context
import android.os.SystemClock
import dev.localassistant.AssistantRuntime
import dev.localassistant.llm.ChatSession
import kotlinx.coroutines.runBlocking

/** Sends one question through the real chat session with tools enabled and reports what happened. */
class ToolTest(private val context: Context, private val report: (String) -> Unit) {

    fun run(question: String) = runBlocking {
        val chat = ChatSession(
            holder = AssistantRuntime.engine,
            scope = AssistantRuntime.scope,
            tools = AssistantRuntime.tools,
            history = AssistantRuntime.history,
        )
        val start = SystemClock.elapsedRealtime()
        var seenTool: String? = null
        chat.send(question)
        while (chat.generating.value && SystemClock.elapsedRealtime() - start < 180_000) {
            AssistantRuntime.tools.activity.value?.let { seenTool = "${it.name}(${it.summary})" }
            SystemClock.sleep(100)
        }
        report("TOOL question='$question' took=${SystemClock.elapsedRealtime() - start}ms tool=$seenTool")
        report("TOOL pending_action=${AssistantRuntime.tools.actions.pending.value?.label}")
        report("TOOL reply='${chat.messages.value.lastOrNull()?.text?.replace('\n', ' ')?.take(300)}'")
        AssistantRuntime.tools.actions.clear()
    }
}
