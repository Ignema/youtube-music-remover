package com.musicremover.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Caches processed results on-device so history playback
 * works even after the server restarts.
 */
class ResultCache(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDir = File(context.filesDir, "results").apply { mkdirs() }

    fun getCachedFile(jobId: String): File? {
        val files = cacheDir.listFiles { f -> f.name.startsWith(jobId) }
        return files?.firstOrNull()?.takeIf { it.exists() && it.length() > 0 }
    }

    fun isCached(jobId: String): Boolean = getCachedFile(jobId) != null

    /** FileProvider content:// URI for a file inside the app's shareable dirs */
    fun contentUri(file: File): Uri =
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)

    /** content:// URI of the cached result for a job, or null if not cached */
    fun cachedUri(jobId: String): Uri? = getCachedFile(jobId)?.let { contentUri(it) }

    /**
     * Download a result from [downloadUrl] and cache it locally.
     * Returns the cached file, or null on failure.
     */
    fun cacheFromUrl(downloadUrl: String, jobId: String, filename: String): File? {
        val file = File(cacheDir, "${jobId}_${filename}")
        if (file.exists() && file.length() > 0) return file
        // Must not start with jobId, so getCachedFile never returns a partial download
        val tmp = File(cacheDir, ".tmp_$jobId")
        return try {
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.connect()

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }

            val expected = conn.contentLengthLong
            val copied = conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            if ((expected >= 0 && copied != expected) || copied == 0L || !tmp.renameTo(file)) {
                tmp.delete()
                return null
            }
            file
        } catch (_: Exception) {
            tmp.delete()
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
