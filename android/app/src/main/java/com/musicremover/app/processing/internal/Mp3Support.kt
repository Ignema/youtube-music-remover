package com.musicremover.app.processing.internal

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether the bundled ffmpeg can encode MP3 on this phone (`libffmpeg.so -encoders` lists libmp3lame).
 * Never throws except [CancellationException]; only a successful probe is cached, so a transient failure
 * (low storage while unpacking, a timeout) is retried on the next call.
 */
object Mp3Support {
    enum class State { Unknown, Checking, Available, Unavailable }

    const val M4A = "m4a"
    const val MP3 = "mp3"

    private const val TAG = "MuremInternal"
    private const val PROBE_TIMEOUT_MS = 10_000L
    private val LINE = Regex("^\\s*A\\S*\\s+libmp3lame\\s", RegexOption.MULTILINE)

    /** Only `true` is ever stored. */
    @Volatile
    private var cached: Boolean? = null

    /** [probe] exists for tests; production code calls `available(ctx)`. */
    suspend fun available(ctx: Context, probe: suspend (Context) -> Boolean = ::probe): Boolean =
        withContext(Dispatchers.IO) {
            cached?.let { return@withContext it }
            val ok = try {
                probe(ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "mp3 probe failed: ${e.javaClass.simpleName}")
                false
            }
            if (ok) cached = true
            ok
        }

    /**
     * MP3 is written only for a result without video, and only when [available] confirms the encoder;
     * otherwise the worker writes M4A.
     */
    suspend fun wantMp3(format: String, hasVideo: Boolean, available: suspend () -> Boolean): Boolean =
        format == MP3 && !hasVideo && available()

    internal fun parse(encodersOutput: String): Boolean = LINE.containsMatchIn(encodersOutput)

    @VisibleForTesting
    internal fun resetForTest() {
        cached = null
    }

    /** Unpacks the runtime, runs `-hide_banner -encoders` with a 10 s timeout. */
    private suspend fun probe(ctx: Context): Boolean {
        val r = FfmpegRunner.capture(ctx.applicationContext, listOf("-hide_banner", "-encoders"), PROBE_TIMEOUT_MS)
        if (r == null) {
            Log.w(TAG, "mp3 probe failed: timeout")
            return false
        }
        if (r.exitCode != 0) {
            Log.w(TAG, "mp3 probe failed: exit ${r.exitCode}")
            return false
        }
        val ok = parse(r.output)
        val line = r.output.lineSequence().firstOrNull { "libmp3lame" in it }?.trim()
        Log.i(TAG, "mp3 probe: ${if (ok) line else "no libmp3lame encoder"}")
        return ok
    }
}
