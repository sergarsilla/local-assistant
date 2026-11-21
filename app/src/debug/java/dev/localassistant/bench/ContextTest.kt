package dev.localassistant.bench

import android.os.SystemClock
import dev.localassistant.AssistantRuntime
import dev.localassistant.llm.ChatSession
import kotlinx.coroutines.runBlocking

/**
 * Sends long monologues, like a video picked up by the microphone, until the conversation no
 * longer fits the context window, and reports how full it gets and whether every turn is answered.
 */
class ContextTest(private val report: (String) -> Unit) {

    fun run() = runBlocking {
        val chat = ChatSession(AssistantRuntime.engine, AssistantRuntime.scope)
        repeat(TURNS) { turn ->
            val start = SystemClock.elapsedRealtime()
            chat.send("Turn ${turn + 1}. " + MONOLOGUE + " Answer in one short sentence: what is this about?")
            SystemClock.sleep(200)
            while (chat.generating.value) {
                if (SystemClock.elapsedRealtime() - start > TURN_TIMEOUT_MS) break
                SystemClock.sleep(100)
            }
            val reply = chat.messages.value.lastOrNull()?.takeIf { it.role.name == "ASSISTANT" }?.text.orEmpty()
            val context = chat.context.value
            report(
                "CTX turn=${turn + 1} took=${SystemClock.elapsedRealtime() - start}ms " +
                    "fill=%.2f forgotten=${context.forgotten} warn=${context.nearlyFull} error=${chat.error.value} ".format(context.fill) +
                    "reply='${reply.replace('\n', ' ').take(80)}'",
            )
        }
    }

    private companion object {
        const val TURNS = 12
        const val TURN_TIMEOUT_MS = 180_000L
        val MONOLOGUE = """
            The hardest part was making all these printed pieces find their place and make sense,
            because gluing them was the real problem. We needed something strong like super glue,
            litres of it, and if two pieces were glued wrong they had to be printed again from zero.
            Once we found the direction of every part, we sanded the joints a little to remove the
            natural ridges of the print so the bond would hold, then spread a few drops of glue and
            held it for thirty seconds. After that we sanded horizontally with coarse paper, then
            vertically with four hundred grit, and painted two or three thin coats of satin black.
            The components were the easiest part to model: a motherboard and even liquid cooling sit
            in place without supports, holes or screws, and the radiator rests on the side panel.
            It is more of a showpiece than a finished case, but with a few changes it could work.
        """.trimIndent().replace('\n', ' ').repeat(2)
    }
}
