package com.musicremover.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.musicremover.app.R

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
            .setContentTitle(context.getString(R.string.app_name) + " · ${progress}%")
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
        manager.cancel(NOTIFICATION_ID)

        val serverUrl = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("server_url", "") ?: ""
        val jobId = context.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getString("last_done_job_id", "") ?: ""
        val streamUrl = "$serverUrl/api/download/$jobId"

        val launchClass = try {
            Class.forName("com.musicremover.app.MainActivity")
        } catch (_: Exception) { null }

        val playIntent = if (launchClass != null) {
            PendingIntent.getActivity(
                context, 10,
                Intent(context, launchClass).apply {
                    action = "com.musicremover.app.ACTION_PLAY"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("play_url", streamUrl)
                    putExtra("play_title", filename)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else tapIntent

        val shareIntent = if (launchClass != null) {
            PendingIntent.getActivity(
                context, 11,
                Intent(context, launchClass).apply {
                    action = "com.musicremover.app.ACTION_SHARE"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("share_url", streamUrl)
                    putExtra("share_filename", filename)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else tapIntent

        manager.notify(DONE_NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_done, filename))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .addAction(android.R.drawable.ic_media_play, context.getString(R.string.play), playIntent)
            .addAction(android.R.drawable.ic_menu_share, context.getString(R.string.share), shareIntent)
            .build())
    }

    fun showError(message: String) {
        manager.cancel(NOTIFICATION_ID)
        manager.notify(DONE_NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_error, message))
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
