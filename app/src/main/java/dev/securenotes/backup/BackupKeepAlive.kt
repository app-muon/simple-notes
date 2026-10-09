package dev.securenotes.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import dev.securenotes.NotesApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the process running while a backup finishes after the user leaves the app. Without it Android freezes the app
 * within seconds and the save completes only when the app is next opened. It holds no data and stops once
 * [NotesApplication.keptAlive] drops to zero or the short-service time limit ends.
 */
class BackupKeepAlive : Service() {
    private var waiting: Job? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Saving encrypted backup").setOngoing(true).build()
        // Required promptly after startForegroundService, even when the save has already finished.
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        val app = application as NotesApplication
        waiting?.cancel()
        // stopSelf(startId) is ignored if a newer start is queued, so a save starting now is not cut off.
        waiting = app.scope.launch { app.keptAlive.first { it == 0 }; stopSelf(startId) }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int) { stopSelf() }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
    override fun onDestroy() { waiting?.cancel(); super.onDestroy() }
    private companion object {
        const val CHANNEL = "backup"
        const val NOTIFICATION = 1
    }
}
