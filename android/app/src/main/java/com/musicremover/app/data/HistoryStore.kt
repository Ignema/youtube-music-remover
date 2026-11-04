package com.musicremover.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
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
    /** Processing mode that made the result (BackendProvider.MODE_*); null for items saved before it was recorded */
    val processedBy: String? = null,
)

/**
 * No anonymous TypeToken subclass, so R8 cannot strip its generic signature. History is read when the
 * app starts: an unreadable value must give an empty list, not stop the app from opening.
 */
internal fun parseHistory(gson: Gson, json: String?): List<HistoryItem> {
    if (json == null) return emptyList()
    val type = TypeToken.getParameterized(List::class.java, HistoryItem::class.java).type
    return try {
        gson.fromJson<List<HistoryItem>>(json, type) ?: emptyList()
    } catch (e: JsonParseException) {
        Log.w("HistoryStore", "Unreadable history, ignoring it", e)
        emptyList()
    } catch (e: IllegalStateException) {
        Log.w("HistoryStore", "Unreadable history, ignoring it", e)
        emptyList()
    }
}

class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "items"
    private val savedJobIds = mutableSetOf<String>()

    fun getAll(): List<HistoryItem> = parseHistory(gson, prefs.getString(key, null))

    // Shared by all instances: several HistoryStores in one process may add the same job
    fun add(item: HistoryItem) {
        synchronized(LOCK) {
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
    }

    private companion object {
        val LOCK = Any()
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }

    fun replace(items: List<HistoryItem>) {
        prefs.edit().putString(key, gson.toJson(items)).apply()
    }
}
