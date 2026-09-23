package com.novelscraper.app.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.novelscraper.app.platform.appContext
import com.novelscraper.app.shared.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps chapter downloads going while the app is in the background: Android
 * stops work in a backgrounded process, so a queue with chapters left in it runs
 * under a foreground service with a progress notification. [DownloadKeeper]
 * starts it when the queue fills; it stops itself when the queue empties.
 */
class DownloadService : Service() {

    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(notification(0, null))
        if (watcher == null) {
            watcher = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                combine(Library.store.queuedFlow(), ChapterDownloads.state) { left, state -> left to state.error }
                    .distinctUntilChanged()
                    .collect { (left, error) ->
                        if (left == 0L) {
                            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                                .notify(NOTIF_ID, notification(left, error))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    private fun startForeground(n: Notification) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun notification(left: Long, error: String?): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Chapter downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Chapters being saved for offline reading"
                    setShowBadge(false)
                },
            )
        }
        val text = error ?: when (left) {
            0L -> "Finishing…"
            1L -> "1 chapter to go"
            else -> "$left chapters to go"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Downloading chapters")
            .setContentText(text)
            .setOngoing(error == null)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, error == null)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIF_ID = 43
    }
}

/** Starts [DownloadService] whenever chapters are queued (Android only). */
object DownloadKeeper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start() {
        scope.launch {
            Library.store.queuedFlow().distinctUntilChanged().collect { left ->
                if (left > 0) {
                    runCatching {
                        ContextCompat.startForegroundService(appContext, Intent(appContext, DownloadService::class.java))
                    }
                }
            }
        }
    }
}
