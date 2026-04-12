package com.musicremover.app.data

import android.content.Context
import com.musicremover.app.BuildConfig

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", null) ?: BuildConfig.API_BASE_URL
        set(value) = prefs.edit().putString("server_url", value).apply()
}
