package com.musicremover.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationHelper(private val context: Context) {
    companion object {
        const val CHANNEL_PROGRESS = "processing"
        const val CHANNEL_DONE = "completed"
        const val CHANNEL_ID = CHANNEL_PROGRESS // For ForegroundService compat
        const val NOTIFICATION_ID = 1
        private const val DONE_NOTIFICATION_ID = 2
    }

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val tapIntent: PendingIntent by lazy {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Silent progress channel
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_PROGRESS, "Processing", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress while processing videos"
                setShowBadge(false)
            })
            // Audible completion channel
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_DONE, "Completed", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifies when processing is done"
            })
        }
    }

    fun buildProgress(status: String, progress: Int): Notification {
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Murem · ${progress}%")
            .setContentText(status)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .build()
    }

    fun showProgress(status: String, progress: Int) {
        manager.notify(NOTIFICATION_ID, buildProgress(status, progress))
    }

    fun showDone(filename: String) {
        // Dismiss the progress notification
        manager.cancel(NOTIFICATION_ID)
        // Show a new one on the audible channel
        manager.notify(DONE_NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Murem")
            .setContentText("Done — $filename")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build())
    }

    fun showError(message: String) {
        manager.cancel(NOTIFICATION_ID)
        manager.notify(DONE_NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Murem")
            .setContentText("Error — $message")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build())
    }

    fun dismiss() {
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(DONE_NOTIFICATION_ID)
    }
}
