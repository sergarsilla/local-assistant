package dev.localassistant.bench

import android.content.Context
import android.os.SystemClock
import dev.localassistant.AssistantRuntime
import dev.localassistant.llm.ChatSession
import dev.localassistant.voice.VoiceController
import kotlinx.coroutines.runBlocking

/**
 * Drives the real conversation loop through one spoken reply to check that the assistant does not
 * cut itself off while its own voice is in the room.
 */
class ConversationTest(private val context: Context, private val report: (String) -> Unit) {

    fun run() = runBlocking {
        val chat = ChatSession(AssistantRuntime.engine, AssistantRuntime.scope)
        val voice = VoiceController(
            context = context,
            chat = chat,
            holder = AssistantRuntime.speech,
            settings = AssistantRuntime.voiceSettings,
            scope = AssistantRuntime.scope,
        )
        voice.startConversation()
        SystemClock.sleep(2_000)
        report("CONV conversation_active=${voice.conversation.value}")

        val start = SystemClock.elapsedRealtime()
        voice.ask(QUESTION)
        while (chat.generating.value || voice.speaker.speaking.value) {
            if (SystemClock.elapsedRealtime() - start > 120_000) break
            SystemClock.sleep(100)
        }
        val reply = chat.messages.value.lastOrNull()?.text.orEmpty()
        report("CONV spoke_for=${SystemClock.elapsedRealtime() - start}ms reply_chars=${reply.length}")
        report("CONV turns=${chat.messages.value.size} still_listening=${voice.conversation.value}")
        report("CONV reply='${reply.replace('\n', ' ').take(160)}'")
        voice.close()
    }

    private companion object {
        const val QUESTION = "Cuéntame en voz alta un chiste corto y luego explícalo en dos frases."
    }
}
