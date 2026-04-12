package com.musicremover.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.musicremover.app.data.NotificationHelper

/**
 * Foreground service that keeps the app alive during processing.
 * Started when processing begins, stopped when done/error.
 */
class ProcessingService : Service() {
    private lateinit var notif: NotificationHelper

    override fun onCreate() {
        super.onCreate()
        notif = NotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val status = intent?.getStringExtra("status") ?: "Processing…"
        val progress = intent?.getIntExtra("progress", 0) ?: 0
        val action = intent?.getStringExtra("action")

        if (action == "stop") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = notif.buildProgress(status, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NotificationHelper.NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context, status: String, progress: Int) {
            val intent = Intent(context, ProcessingService::class.java).apply {
                putExtra("status", status)
                putExtra("progress", progress)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProcessingService::class.java).apply {
                putExtra("action", "stop")
            }
            context.startService(intent)
        }
    }
}
