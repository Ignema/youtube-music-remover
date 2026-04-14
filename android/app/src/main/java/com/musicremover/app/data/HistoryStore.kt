package com.musicremover.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class HistoryItem(
    val filename: String,
    val url: String,
    val model: String,
    val jobId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFileUpload: Boolean = false,
    val sourceFileName: String? = null,
    val sourceFileSize: Long? = null,
    val sourceFilePath: String? = null,
    // Cached YouTube metadata
    val ytTitle: String? = null,
    val ytChannel: String? = null,
    val ytThumbnail: String? = null,
    val ytDuration: Int = 0,
    val ytViewCount: Long = 0,
    val ytUploadDate: String? = null,
    val ytDescription: String? = null,
)

class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "items"
    private val savedJobIds = mutableSetOf<String>()

    fun getAll(): List<HistoryItem> {
        val json = prefs.getString(key, null) ?: return emptyList()
        val type = object : TypeToken<List<HistoryItem>>() {}.type
        return gson.fromJson(json, type)
    }

    @Synchronized
    fun add(item: HistoryItem) {
        if (item.jobId in savedJobIds) return
        val list = getAll().toMutableList()
        if (list.any { it.jobId == item.jobId }) {
            savedJobIds.add(item.jobId)
            return
        }
        list.add(0, item)
        savedJobIds.add(item.jobId)
        val trimmed = list.take(50)
        prefs.edit().putString(key, gson.toJson(trimmed)).apply()
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }

    fun replace(items: List<HistoryItem>) {
        prefs.edit().putString(key, gson.toJson(items)).apply()
    }
}
