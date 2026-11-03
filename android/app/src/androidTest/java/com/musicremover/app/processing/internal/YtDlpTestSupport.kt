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
 * - `muremProxy=http://127.0.0.1:<port>`: HTTPS proxy for yt-dlp, see [applyProxy]. Needed for Instagram on
 *   the emulator.
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

    /**
     * Sets `https_proxy` for the yt-dlp child processes when `muremProxy` is given (e.g.
     * `http://127.0.0.1:8899` after `adb reverse tcp:8899 tcp:8899` to a CONNECT proxy on the host). The
     * emulator's NAT truncates HTTPS responses that end with a server close, which breaks Instagram pages.
     */
    @Synchronized
    fun applyProxy() {
        val p = args.getString("muremProxy")?.takeIf { it.isNotBlank() } ?: return
        android.system.Os.setenv("https_proxy", p, true)
        android.system.Os.setenv("HTTPS_PROXY", p, true)
        Log.i(TAG, "proxy $p")
    }

    /** [applyProxy], then "Update yt-dlp" once per run if `muremUpdateYtDlp=true`. */
    @Synchronized
    fun maybeUpdate(ctx: Context) {
        applyProxy()
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
