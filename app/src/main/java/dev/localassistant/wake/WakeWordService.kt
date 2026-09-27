package dev.localassistant.wake

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.localassistant.AssistantRuntime
import dev.localassistant.inAppLanguage
import dev.localassistant.R
import dev.localassistant.assistant.AssistantService
import dev.localassistant.ui.MainActivity
import dev.localassistant.ui.navigation.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the keyword spotter listening in the background and opens the assistant when it hears
 * the phrase. Android requires a visible notification for this; it also offers a way to stop.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            AssistantRuntime.wakeWord.update { it.copy(enabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (loop?.isActive != true) loop = scope.launch { listenLoop() }
        return START_STICKY
    }

    private suspend fun listenLoop() {
        val settings = AssistantRuntime.wakeWord
        val detector = WakeWordDetector(this)
        // Restarts listening whenever the phrase changes or the panel releases the microphone.
        combine(settings.state, settings.paused) { prefs, paused -> prefs to paused }.collectLatest { (prefs, paused) ->
            if (!prefs.enabled) {
                stopSelf()
                return@collectLatest
            }
            if (paused) return@collectLatest
            while (true) {
                val heard = runCatching { detector.awaitPhrase(prefs.phrase) }
                    .onFailure { Log.w(TAG, "Keyword spotting failed", it) }
                    .getOrDefault(false)
                if (heard) {
                    if (!AssistantService.open(wakeWord = true)) Log.i(TAG, "Heard the phrase, but this app is not the assistant")
                    // Gives the panel time to take the microphone (and pause this loop) before the
                    // spotter would open it again.
                    delay(HANDOVER_DELAY_MS)
                } else {
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val strings = inAppLanguage()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, strings.getString(R.string.wake_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val disable = PendingIntent.getService(
            this,
            0,
            Intent(this, WakeWordService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            MainActivity.intent(this, Destination.SETTINGS),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val phrase = AssistantRuntime.wakeWord.state.value.phrase.label
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(strings.getString(R.string.wake_notification_title, phrase))
            .setContentText(strings.getString(R.string.wake_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, strings.getString(R.string.wake_disable), disable).build())
            .build()
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_DISABLE = "disable"
        private const val RETRY_DELAY_MS = 2_000L
        private const val HANDOVER_DELAY_MS = 1_500L

        /**
         * Starts listening if the user turned it on. Android only lets a microphone service start
         * while the app is in use, so this is called from the app and the assistant panel.
         */
        fun syncWithSettings(context: Context) {
            val enabled = AssistantRuntime.wakeWord.state.value.enabled
            val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val intent = Intent(context, WakeWordService::class.java)
            if (!enabled || !mic) {
                context.stopService(intent)
                return
            }
            try {
                context.startForegroundService(intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.i(TAG, "Not allowed to start listening from the background yet", e)
            } catch (e: SecurityException) {
                Log.i(TAG, "Not allowed to use the microphone from the background yet", e)
            }
        }
    }
}
