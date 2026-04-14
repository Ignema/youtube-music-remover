package com.musicremover.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PLAY = "com.musicremover.app.ACTION_PLAY"
        const val ACTION_SHARE = "com.musicremover.app.ACTION_SHARE"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILENAME = "extra_filename"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "result"

        when (intent.action) {
            ACTION_PLAY -> {
                val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(playIntent)
            }
            ACTION_SHARE -> {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (filename.endsWith(".mp3")) "audio/mpeg" else "video/mp4"
                    putExtra(Intent.EXTRA_TEXT, url)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        }
    }
}
