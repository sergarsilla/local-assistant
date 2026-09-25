package dev.localassistant.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log
import dev.localassistant.wake.WakeWordService
import java.lang.ref.WeakReference

class AssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        current = WeakReference(this)
        Log.i(TAG, "Voice interaction service ready")
        WakeWordService.syncWithSettings(this)
    }

    override fun onShutdown() {
        if (current?.get() === this) current = null
        super.onShutdown()
    }

    companion object {
        const val TAG = "LocalAssistant"

        /** Set when a panel is opened to continue a saved conversation. */
        const val EXTRA_CONVERSATION_ID = "conversation_id"

        /** Set when the wake word opened the panel: it listens straight away. */
        const val EXTRA_WAKE_WORD = "wake_word"

        @Volatile
        private var current: WeakReference<AssistantService>? = null

        /** True while this app is the active assistant and its service is running. */
        val isActive: Boolean get() = current?.get() != null

        /**
         * Opens the assistant panel, optionally on a saved conversation. Returns false when this
         * app is not the phone's assistant, so the caller can explain why nothing happened.
         */
        fun open(conversationId: Long? = null, wakeWord: Boolean = false): Boolean {
            val service = current?.get() ?: return false
            val args = Bundle().apply {
                conversationId?.let { putLong(EXTRA_CONVERSATION_ID, it) }
                putBoolean(EXTRA_WAKE_WORD, wakeWord)
            }
            service.showSession(args, 0)
            return true
        }
    }
}
