package com.musicremover.app.data

import android.content.Context
import com.musicremover.app.BuildConfig

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", null) ?: BuildConfig.API_BASE_URL
        set(value) = prefs.edit().putString("server_url", value).apply()

    /** "internal", "termux", or "server". Migrated once from [serverUrl] when unset. */
    var processingMode: String
        get() {
            val stored = prefs.getString("processing_mode", null)
            if (stored in PROCESSING_MODES) return stored!!
            val migrated = migrateProcessingMode(prefs.getString("server_url", null))
            prefs.edit().putString("processing_mode", migrated).apply()
            return migrated
        }
        set(value) {
            require(value in PROCESSING_MODES) { "Invalid processing mode: $value" }
            prefs.edit().putString("processing_mode", value).apply()
        }

    /** "system", "light", or "dark" */
    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    /** Whether to use Material You dynamic colors (Android 12+) */
    var dynamicColor: Boolean
        get() = prefs.getBoolean("dynamic_color", true)
        set(value) = prefs.edit().putBoolean("dynamic_color", value).apply()

    /** Language code, e.g. "en", "ar", "fr". Empty = system default */
    var language: String
        get() = prefs.getString("language", "") ?: ""
        set(value) = prefs.edit().putString("language", value).apply()

    /** Widget theme: "orange", "light", "dark", "transparent" */
    var widgetTheme: String
        get() = prefs.getString("widget_theme", "orange") ?: "orange"
        set(value) = prefs.edit().putString("widget_theme", value).apply()

    companion object {
        val PROCESSING_MODES = setOf("internal", "termux", "server")
    }
}

/** Initial processing mode for users upgrading from the URL-only setting. */
fun migrateProcessingMode(serverUrl: String?): String =
    if (serverUrl == "http://127.0.0.1:8000") "termux" else "server"
