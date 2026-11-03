package com.musicremover.app.processing.internal

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking

/**
 * Shared setup for the yt-dlp instrumented tests.
 *
 * Instrumentation arguments (`-Pandroid.testInstrumentationRunnerArguments.<name>=<value>`):
 * - `muremTestUrl`: link to test instead of "Me at the zoo" (fallback when YouTube blocks the emulator).
 * - `muremUpdateYtDlp=true`: run "Update yt-dlp" once before the first test of the run. AGP uninstalls the
 *   app after the run, so the update only lasts for that run.
 */
object YtDlpTestSupport {
    const val TAG = "MuremYtDlpTest"
    const val DEFAULT_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"

    private val args get() = InstrumentationRegistry.getArguments()

    /** The `muremTestUrl` argument, or null for the default YouTube video. */
    val overrideUrl: String? get() = args.getString("muremTestUrl")?.takeIf { it.isNotBlank() }

    val url: String get() = overrideUrl ?: DEFAULT_URL

    /** The internet check: youtube.com for the default video, else the override URL itself. */
    fun internetAvailable(): Boolean = reachable(if (overrideUrl == null) "https://www.youtube.com" else url)

    private fun reachable(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            c.responseCode in 200..399
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "HEAD $url failed", e)
        false
    }

    private var updated = false

    @Synchronized
    fun maybeUpdate(ctx: Context) {
        if (updated || args.getString("muremUpdateYtDlp") != "true") return
        updated = true
        val r = runBlocking { YtDlpRuntime.update(ctx) }
        Log.i(TAG, "Update yt-dlp: $r")
    }

    /** MIME types of the tracks in [file]. */
    fun trackMimes(file: File): List<String> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            return (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        } finally {
            ex.release()
        }
    }
}
