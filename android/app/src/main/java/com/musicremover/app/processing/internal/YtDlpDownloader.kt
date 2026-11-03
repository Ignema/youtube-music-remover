package com.musicremover.app.processing.internal

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Link metadata and downloads through the bundled yt-dlp. Video and audio are downloaded as separate files
 * (no ffmpeg merge, `--fixup never --downloader native`), so the yt-dlp Python process is the only process
 * that writes into the work dir, and [runYtDlp] kills it on cancel. Besides the Python process, yt-dlp may
 * run a short-lived QuickJS child, which writes nothing into workDir. Every function inits the runtime and
 * switches to IO itself. The info JSON is never logged.
 */
class YtDlpDownloader(context: Context) {
    private val ctx = context.applicationContext

    /** `--dump-json` for [url] (already normalized). [processId] must be unique. Throws [YtDlpException]. */
    suspend fun info(url: String, processId: String): YtDlpInfo = withContext(Dispatchers.IO) {
        YtDlpRuntime.ensureInit(ctx)
        val req = YoutubeDLRequest(emptyList()).addCommands(YtDlpRequests.infoArgs(url))
        YtDlpJson.parse(execute(req, processId, null))
    }

    /**
     * Downloads the formats for [info] into `workDir/dl`, writing `workDir/info.json` first, and picks the
     * audio and video files. [onProgress] gets 0..100. Throws [YtDlpException].
     */
    suspend fun download(
        info: YtDlpInfo,
        workDir: File,
        audioOnly: Boolean,
        processId: String,
        onProgress: (Int) -> Unit,
    ): DownloadedMedia = withContext(Dispatchers.IO) {
        YtDlpRuntime.ensureInit(ctx)
        val infoFile = File(workDir, "info.json")
        infoFile.writeText(info.rawJson)
        val outDir = File(workDir, "dl")
        outDir.mkdirs()
        val progress = DownloadProgress(if (audioOnly) 1 else 2)
        val req = YoutubeDLRequest(emptyList()).addCommands(YtDlpRequests.downloadArgs(infoFile, outDir, audioOnly))
        // The library's Float is sticky; only the line is used
        execute(req, processId) { _, _, line -> progress.onLine(line)?.let(onProgress) }
        val files = outDir.listFiles()?.filter { it.isFile && !isPartial(it.name) }.orEmpty()
        val probes = files.map(::probe)
        for (p in probes) Log.i(TAG, "$processId: ${p.file.name} ${p.size} bytes audio=${p.hasAudio} video=${p.hasVideo}")
        DownloadedMedia.choose(probes, audioOnly)
    }

    private suspend fun execute(req: YoutubeDLRequest, processId: String, callback: ((Float, Long, String) -> Unit)?): String =
        try {
            runYtDlp(processId) { YoutubeDL.getInstance().execute(req, processId, callback).out }
        } catch (e: YoutubeDLException) {
            // 0.18.1 puts the process's full stderr into the message
            val stderr = e.message.orEmpty()
            val kind = YtDlpErrors.classify(stderr)
            Log.w(TAG, "yt-dlp $processId failed ($kind): ${YtDlpErrors.tail(stderr)}")
            throw YtDlpException(kind, YtDlpErrors.tail(stderr), e)
        } catch (e: YoutubeDL.CanceledException) {
            // Only while still active (runYtDlp turns it into cancellation otherwise)
            Log.w(TAG, "yt-dlp $processId stopped without a cancel", e)
            throw YtDlpException(YtDlpErrors.Kind.OTHER, "yt-dlp process was stopped", e)
        } catch (e: InterruptedException) {
            Log.w(TAG, "yt-dlp $processId interrupted", e)
            throw YtDlpException(YtDlpErrors.Kind.OTHER, "yt-dlp was interrupted", e)
        }

    private fun probe(file: File): DownloadedMedia.Probe {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val mimes = (0 until extractor.trackCount).mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            DownloadedMedia.Probe(file, file.length(), mimes.any { it.startsWith("audio/") }, mimes.any { it.startsWith("video/") })
        } catch (e: Exception) {
            Log.w(TAG, "cannot probe ${file.name}", e)
            DownloadedMedia.Probe(file, file.length(), hasAudio = false, hasVideo = false)
        } finally {
            extractor.release()
        }
    }

    private fun isPartial(name: String): Boolean =
        name.endsWith(".part") || name.endsWith(".ytdl") || name.endsWith(".json") || name.contains(".temp")

    private companion object {
        const val TAG = "MuremYtDlp"
    }
}
