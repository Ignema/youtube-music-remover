package com.musicremover.app.processing.internal

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the ffmpeg binary that youtubedl-android ships (`libffmpeg.so` in the native lib dir), with the
 * same library path the library uses. Arguments are always a list, never a shell string. The caller owns
 * the process, so a coroutine cancel destroys it.
 */
object FfmpegRunner {
    private const val TAG = "MuremInternal"
    private const val TAIL_CHARS = 2_000
    private const val TAIL_BYTES = 8 * 1024L
    private const val EXIT_WAIT_MS = 2_000L
    private const val CAPTURE_MAX_BYTES = 256 * 1024
    const val LOG_NAME = "ffmpeg.log"

    /**
     * [exitCode] is -1 if ffmpeg did not exit in time. [inputError] is set when writing stdin failed
     * (EPIPE once ffmpeg has quit); [stderrTail] is then read after ffmpeg had 2 s to exit.
     */
    data class Result(val exitCode: Int, val stderrTail: String, val inputError: IOException? = null)

    /** Output of a [capture] run (stdout and stderr merged, at most 256 KB). */
    data class Captured(val exitCode: Int, val output: String)

    fun binary(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    /**
     * Encode run: [stdin] writes the input (the stream is closed afterwards), stdout and stderr go to
     * `workDir/ffmpeg.log`. Waits up to [timeoutAfterInputMs] for the exit once the input is written.
     * Throws [IOException] if the process cannot start and [YtDlpException] if the runtime cannot be unpacked.
     */
    suspend fun run(
        ctx: Context,
        args: List<String>,
        workDir: File,
        stdin: (OutputStream) -> Unit,
        timeoutAfterInputMs: Long = 30_000,
    ): Result = withContext(Dispatchers.IO) {
        val log = File(workDir, LOG_NAME)
        val process = start(ctx, args) {
            redirectErrorStream(true)
            redirectOutput(ProcessBuilder.Redirect.to(log))
        }
        try {
            var inputError: IOException? = null
            try {
                cancellableBlocking(onCancel = { process.destroy() }) {
                    process.outputStream.use(stdin)
                }
            } catch (e: IOException) {
                inputError = e
            }
            val waitMs = if (inputError != null) EXIT_WAIT_MS else timeoutAfterInputMs
            val exited = cancellableBlocking(onCancel = { process.destroy() }) {
                process.waitFor(waitMs, TimeUnit.MILLISECONDS)
            }
            val code = if (exited) process.exitValue() else {
                Log.w(TAG, "ffmpeg did not exit within $waitMs ms")
                process.destroyForcibly()
                -1
            }
            Result(code, tail(log), inputError)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { stop(process) }
            throw e
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * Short run without input (the probe): stdout stays a pipe with stderr merged into it and is read
     * fully before the exit is awaited. Returns null after [timeoutMs].
     */
    suspend fun capture(ctx: Context, args: List<String>, timeoutMs: Long): Captured? = withContext(Dispatchers.IO) {
        val process = start(ctx, args) { redirectErrorStream(true) }
        try {
            process.outputStream.close()
            withTimeoutOrNull(timeoutMs) {
                val out = cancellableBlocking(onCancel = { process.destroy() }) { readCapped(process.inputStream) }
                val exited = cancellableBlocking(onCancel = { process.destroy() }) {
                    process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
                }
                if (exited) Captured(process.exitValue(), out) else null
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { stop(process) }
            throw e
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun start(ctx: Context, args: List<String>, configure: ProcessBuilder.() -> Unit): Process {
        YtDlpRuntime.ensureInit(ctx)
        val packages = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        val pb = ProcessBuilder(listOf(binary(ctx).absolutePath) + args)
        pb.environment()["LD_LIBRARY_PATH"] =
            File(packages, "python/usr/lib").absolutePath + ":" + File(packages, "ffmpeg/usr/lib").absolutePath
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
        pb.configure()
        return pb.start()
    }

    private fun stop(process: Process) {
        process.destroy()
        if (!process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }

    /** Reads to EOF; keeps the first 256 KB and drops the rest so ffmpeg never blocks on a full pipe. */
    private fun readCapped(input: InputStream): String {
        val kept = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                val room = CAPTURE_MAX_BYTES - kept.size()
                if (room > 0) kept.write(buf, 0, minOf(n, room))
            }
        }
        return kept.toString(Charsets.UTF_8.name())
    }

    /** Last 2,000 chars of the log, "" if it is missing. */
    private fun tail(log: File): String = try {
        RandomAccessFile(log, "r").use { raf ->
            val start = maxOf(0L, raf.length() - TAIL_BYTES)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.seek(start)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8).takeLast(TAIL_CHARS)
        }
    } catch (_: IOException) {
        ""
    }
}
