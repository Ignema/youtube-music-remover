package com.musicremover.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
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
    private val files = ResultFiles(cacheDir)

    /** The completed (marker-verified) result for a job, or null */
    fun getCachedFile(jobId: String): File? = files.getCachedFile(jobId)

    /** A non-empty result without a valid marker (pre-upgrade or truncated). Last resort only. */
    fun getUnverifiedFile(jobId: String): File? = files.getUnverifiedFile(jobId)

    fun isCached(jobId: String): Boolean = getCachedFile(jobId) != null

    /** Temp file for a result being written; pass it to [commit] when complete */
    fun tempFile(jobId: String): File = files.tempFile(jobId)

    /** Move a complete [tmp] into place as the result of [jobId]. Returns null on failure. */
    fun commit(jobId: String, filename: String, tmp: File): File? =
        files.commit(jobId, filename, tmp) { e ->
            Log.w(TAG, "Cannot write completion marker for $jobId", e)
        }

    /** Delete temps and markers left by jobs not in [keepJobIds] */
    fun cleanStale(keepJobIds: Set<String>) = files.cleanStale(keepJobIds)

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
        getCachedFile(jobId)?.let { return it }
        val tmp = tempFile(jobId)
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
            if ((expected >= 0 && copied != expected) || copied == 0L) {
                tmp.delete()
                return null
            }
            commit(jobId, filename, tmp)
        } catch (_: Exception) {
            tmp.delete()
            null
        }
    }

    /** Total cache size in bytes */
    fun totalSize(): Long = files.totalSize()

    /** Clear all cached results */
    fun clear() = files.clear()

    /** Delete a specific cached result */
    fun delete(jobId: String) = files.delete(jobId)

    private companion object {
        const val TAG = "ResultCache"
    }
}
