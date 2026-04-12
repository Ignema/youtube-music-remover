package com.musicremover.app.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Caches processed results on-device so history playback
 * works even after the server restarts.
 */
class ResultCache(context: Context) {
    private val cacheDir = File(context.filesDir, "results").apply { mkdirs() }

    fun getCachedFile(jobId: String): File? {
        val files = cacheDir.listFiles { f -> f.name.startsWith(jobId) }
        return files?.firstOrNull()?.takeIf { it.exists() && it.length() > 0 }
    }

    fun isCached(jobId: String): Boolean = getCachedFile(jobId) != null

    /**
     * Download result from server and cache it locally.
     * Returns the cached file, or null on failure.
     */
    fun cacheResult(serverUrl: String, jobId: String, filename: String): File? {
        return try {
            val file = File(cacheDir, "${jobId}_${filename}")
            if (file.exists() && file.length() > 0) return file

            val url = URL("$serverUrl/api/download/$jobId")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.connect()

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }

            conn.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            file
        } catch (_: Exception) {
            null
        }
    }

    /** Total cache size in bytes */
    fun totalSize(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0

    /** Clear all cached results */
    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /** Delete a specific cached result */
    fun delete(jobId: String) {
        cacheDir.listFiles { f -> f.name.startsWith(jobId) }?.forEach { it.delete() }
    }
}
