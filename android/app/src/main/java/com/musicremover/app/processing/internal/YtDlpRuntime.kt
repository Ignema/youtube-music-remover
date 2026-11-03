package com.musicremover.app.processing.internal

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bundled yt-dlp (youtubedl-android): lazy init, version and update. Only internal code paths touch it,
 * so Server and Termux users never unzip Python. Every function switches to IO and inits by itself.
 */
object YtDlpRuntime {
    private const val TAG = "MuremYtDlp"
    private const val UPDATE_LOCK_WAIT_S = 5L

    /** Read side: every `execute` (see [runYtDlp]). Write side: [update], which replaces the binary. */
    val lock = ReentrantReadWriteLock()

    @Volatile
    private var initialized = false

    @Volatile
    private var cachedVersion: String? = null

    sealed interface UpdateResult {
        data class Updated(val version: String) : UpdateResult
        data class UpToDate(val version: String) : UpdateResult
        /** A yt-dlp call held the lock for more than 5 s. */
        data object Busy : UpdateResult
        data object Failed : UpdateResult
    }

    /**
     * Idempotent and blocking: the first call unzips Python and ffmpeg into noBackupFilesDir, which takes
     * seconds. Throws [YtDlpException] with [YtDlpErrors.Kind.INIT].
     */
    @Synchronized
    fun ensureInit(context: Context) {
        if (initialized) return
        val ctx = context.applicationContext
        try {
            YoutubeDL.getInstance().init(ctx)
            FFmpeg.getInstance().init(ctx)
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "yt-dlp init failed", e)
            throw YtDlpException(YtDlpErrors.Kind.INIT, e.message.orEmpty(), e)
        }
        initialized = true
    }

    /** `yt-dlp --version`, cached until the next [update]. Throws [YtDlpException]. */
    suspend fun version(context: Context): String = withContext(Dispatchers.IO) {
        ensureInit(context)
        cachedVersion ?: run {
            val req = YoutubeDLRequest(emptyList()).addOption("--version").addCommands(listOf("--"))
            val id = "version-${UUID.randomUUID()}"
            val out = try {
                runYtDlp(id) { YoutubeDL.getInstance().execute(req, id, null).out }
            } catch (e: YoutubeDLException) {
                throw YtDlpException(YtDlpErrors.classify(e.message.orEmpty()), YtDlpErrors.tail(e.message.orEmpty()), e)
            }
            out.trim().also { cachedVersion = it }
        }
    }

    /**
     * Downloads the latest stable yt-dlp under the write side of [lock] (tryLock 5 s, unlocked in `finally`
     * on the same thread). Never throws except for cancellation.
     */
    suspend fun update(context: Context): UpdateResult = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val status = try {
            ensureInit(ctx)
            val write = lock.writeLock()
            if (!write.tryLock(UPDATE_LOCK_WAIT_S, TimeUnit.SECONDS)) {
                Log.w(TAG, "update: yt-dlp is busy")
                return@withContext UpdateResult.Busy
            }
            try {
                YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
            } finally {
                cachedVersion = null
                write.unlock()
            }
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            return@withContext UpdateResult.Failed
        }
        // The write lock is released: version() takes the read lock on another thread
        val v = try {
            version(ctx)
        } catch (e: YtDlpException) {
            Log.w(TAG, "version after update failed", e)
            YoutubeDL.getInstance().version(ctx).orEmpty()
        }
        val result = if (status == YoutubeDL.UpdateStatus.DONE) UpdateResult.Updated(v) else UpdateResult.UpToDate(v)
        Log.i(TAG, "update: $result")
        result
    }
}
