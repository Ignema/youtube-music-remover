package com.musicremover.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.URL

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
                // Launch app with deep link to player
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent()
                launchIntent.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("play_url", url)
                    putExtra("play_title", filename)
                }
                context.startActivity(launchIntent)
            }
            ACTION_SHARE -> {
                // Download to cache then share via FileProvider
                Thread {
                    try {
                        val cacheDir = File(context.cacheDir, "shared")
                        cacheDir.mkdirs()
                        val file = File(cacheDir, filename)
                        URL(url).openStream().use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        )
                        val mime = if (filename.endsWith(".mp3")) "audio/mpeg" else "video/mp4"
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = Intent.createChooser(shareIntent, "Share").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooser)
                    } catch (_: Exception) {
                        // Silent fail — notification action
                    }
                }.start()
            }
        }
    }
}
