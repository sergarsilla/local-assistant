package dev.localassistant.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.localassistant.AssistantRuntime
import dev.localassistant.R
import dev.localassistant.voice.VoicePack
import dev.localassistant.voice.VoicePacks
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Downloads model files in the foreground so multi-GB transfers survive leaving the app.
 * Partial files are kept as `.part` and resumed with HTTP range requests.
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME) ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_CANCEL -> {
                jobs.remove(fileName)?.cancel()
                Transfers.clear(fileName)
                stopIfIdle()
            }
            ACTION_PACK -> {
                // Started with startForegroundService, so it must go foreground even to bail out.
                startForeground(NOTIFICATION_ID, progressNotification(fileName, 0f),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                val pack = VoicePack.byId(fileName) ?: run {
                    Transfers.clear(fileName)
                    stopIfIdle()
                    return START_NOT_STICKY
                }
                if (jobs[fileName]?.isActive != true) {
                    jobs[fileName] = scope.launch { downloadPack(pack) }
                }
            }
            else -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val source = intent.getStringExtra(EXTRA_SOURCE) ?: url
                startForeground(NOTIFICATION_ID, progressNotification(fileName, 0f),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                if (jobs[fileName]?.isActive != true) {
                    jobs[fileName] = scope.launch { download(url, fileName, source) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun download(url: String, fileName: String, source: String) {
        val store = AssistantRuntime.models
        guarded(fileName, cleanup = { File(store.directory, "$fileName.part").delete() }) {
            var lastReport = 0L
            fetch(url, File(store.directory, fileName)) { done, total ->
                lastReport = reportProgress(fileName, done, total, lastReport)
            }
            store.recordSource(fileName, source)
        }
        store.refresh()
    }

    /** Downloads the files a voice pack needs; the marker file makes a partial pack invisible. */
    private suspend fun downloadPack(pack: VoicePack) {
        val dir = pack.directory(this)
        guarded(pack.id, cleanup = {}) {
            val files = HuggingFaceClient.allFiles(pack.repo).filter { pack.wants(it.path) }
            val total = files.sumOf { it.sizeBytes }
            var finished = 0L
            var lastReport = 0L
            for (file in files) {
                val target = File(dir, file.path)
                if (target.exists() && target.length() == file.sizeBytes) {
                    finished += file.sizeBytes
                    continue
                }
                target.parentFile?.mkdirs()
                fetch(file.url, target) { done, _ ->
                    lastReport = reportProgress(pack.id, finished + done, total, lastReport)
                }
                finished += file.sizeBytes
            }
            File(dir, VoicePack.COMPLETE_MARKER).createNewFile()
        }
        VoicePacks.refresh()
    }

    /** Runs a transfer keyed by [key], turning failures into [TransferState.Failed]. */
    private suspend fun guarded(key: String, cleanup: () -> Unit, block: suspend () -> Unit) {
        try {
            block()
            Transfers.clear(key)
        } catch (e: Exception) {
            if (e is CancellationException) {
                cleanup()
            } else {
                Log.w(TAG, "Download of $key failed", e)
                Transfers.update(TransferState.Failed(key, e.message ?: e.javaClass.simpleName))
            }
        } finally {
            jobs.remove(key)
            stopIfIdle()
        }
    }

    /** Streams [url] into [target] through a resumable `.part` file. */
    private suspend fun fetch(url: String, target: File, onProgress: (done: Long, total: Long) -> Unit) {
        val part = File(target.path + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        if (part.exists()) connection.setRequestProperty("Range", "bytes=${part.length()}-")
        try {
            val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            check(resumed || connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            val offset = if (resumed) part.length() else 0L
            val total = offset + connection.contentLengthLong
            var done = offset
            connection.inputStream.use { input ->
                FileOutputStream(part, resumed).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
            check(total <= 0 || part.length() == total) { "Incomplete download" }
            check(part.renameTo(target)) { "Could not move the file into place" }
        } finally {
            connection.disconnect()
        }
    }

    private fun reportProgress(key: String, done: Long, total: Long, lastReport: Long): Long {
        val now = System.currentTimeMillis()
        if (now - lastReport < PROGRESS_INTERVAL_MS) return lastReport
        Transfers.update(TransferState.Running(key, done, total))
        notifications.notify(NOTIFICATION_ID, progressNotification(key, if (total > 0) done.toFloat() / total else 0f))
        return now
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun progressNotification(fileName: String, fraction: Float): Notification {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val cancel = PendingIntent.getService(
            this, fileName.hashCode(), cancelIntent(this, fileName), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notification_title, fileName))
            .setProgress(1000, (fraction * 1000).toInt(), fraction == 0f)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_cancel), cancel).build())
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ModelDownload"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_PACK = "pack"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_SOURCE = "source"

        fun start(context: Context, file: RemoteModelFile) {
            Transfers.update(TransferState.Running(file.fileName, 0, file.sizeBytes))
            context.startForegroundService(
                Intent(context, ModelDownloadService::class.java)
                    .putExtra(EXTRA_URL, file.url)
                    .putExtra(EXTRA_FILE_NAME, file.fileName)
                    .putExtra(EXTRA_SOURCE, "${file.repo}/${file.path}"),
            )
        }

        fun startPack(context: Context, pack: VoicePack) {
            Transfers.update(TransferState.Running(pack.id, 0, pack.approxBytes))
            context.startForegroundService(
                Intent(context, ModelDownloadService::class.java)
                    .setAction(ACTION_PACK)
                    .putExtra(EXTRA_FILE_NAME, pack.id),
            )
        }

        fun cancel(context: Context, fileName: String) {
            context.startService(cancelIntent(context, fileName))
        }

        private fun cancelIntent(context: Context, fileName: String) =
            Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_FILE_NAME, fileName)
    }
}
