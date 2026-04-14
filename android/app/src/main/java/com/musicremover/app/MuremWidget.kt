package com.musicremover.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class MuremWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        const val ACTION_PASTE_PROCESS = "com.musicremover.app.PASTE_PROCESS"
        const val ACTION_FILE = "com.musicremover.app.WIDGET_FILE"
        const val ACTION_BATCH = "com.musicremover.app.WIDGET_BATCH"
        const val ACTION_SETTINGS = "com.musicremover.app.WIDGET_SETTINGS"

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Apply widget theme
            val theme = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("widget_theme", "orange") ?: "orange"
            val bgRes = when (theme) {
                "light" -> R.drawable.widget_bg_light
                "dark" -> R.drawable.widget_bg_dark
                "transparent" -> R.drawable.widget_bg_transparent
                else -> R.drawable.widget_background
            }
            views.setInt(R.id.widget_root, "setBackgroundResource", bgRes)

            // Text color based on theme
            val textColor = if (theme == "light") 0xFF333333.toInt() else 0xFFFFFFFF.toInt()

            // Apply text colors
            views.setTextColor(R.id.widget_text_paste, textColor)
            views.setTextColor(R.id.widget_text_file, textColor)
            views.setTextColor(R.id.widget_text_batch, textColor)
            views.setTextColor(R.id.widget_text_settings, textColor)

            // Apply icon tint for light theme
            val btnBgRes = if (theme == "light") R.drawable.widget_btn_bg_light else R.drawable.widget_btn_bg
            views.setInt(R.id.widget_paste_process, "setBackgroundResource", btnBgRes)
            views.setInt(R.id.widget_file, "setBackgroundResource", btnBgRes)
            views.setInt(R.id.widget_batch, "setBackgroundResource", btnBgRes)
            views.setInt(R.id.widget_settings, "setBackgroundResource", btnBgRes)

            // Paste & Process — reads clipboard and starts processing
            views.setOnClickPendingIntent(
                R.id.widget_paste_process,
                makePendingIntent(context, ACTION_PASTE_PROCESS, 1),
            )

            // File upload — opens app in file mode
            views.setOnClickPendingIntent(
                R.id.widget_file,
                makePendingIntent(context, ACTION_FILE, 2),
            )

            // Batch — opens app in batch mode
            views.setOnClickPendingIntent(
                R.id.widget_batch,
                makePendingIntent(context, ACTION_BATCH, 3),
            )

            // Settings — opens app settings
            views.setOnClickPendingIntent(
                R.id.widget_settings,
                makePendingIntent(context, ACTION_SETTINGS, 4),
            )

            // Logo — opens app
            val openIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java)
            openIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            views.setOnClickPendingIntent(
                R.id.widget_logo,
                PendingIntent.getActivity(
                    context, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            appWidgetManager.updateAppWidget(widgetId, views)
        }

        private fun makePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                this.action = action
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
