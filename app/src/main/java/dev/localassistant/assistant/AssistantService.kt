package dev.localassistant.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

class AssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(TAG, "Voice interaction service ready")
    }

    companion object {
        const val TAG = "LocalAssistant"
    }
}
