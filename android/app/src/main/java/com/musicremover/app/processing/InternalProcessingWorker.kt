package com.musicremover.app.processing

import ai.onnxruntime.OrtException
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.musicremover.app.R
import com.musicremover.app.data.HistoryStore
import com.musicremover.app.data.NotificationHelper
import com.musicremover.app.data.ResultCache
import com.musicremover.app.data.VideoInfo
import com.musicremover.app.processing.internal.AudioDecoder
import com.musicremover.app.processing.internal.InternalCapability
import com.musicremover.app.processing.internal.InternalJobException
import com.musicremover.app.processing.internal.MdxParams
import com.musicremover.app.processing.internal.MdxSeparator
import com.musicremover.app.processing.internal.ModelCatalog
import com.musicremover.app.processing.internal.ModelCorruptException
import com.musicremover.app.processing.internal.ModelManager
import com.musicremover.app.processing.internal.ModelStorageException
import com.musicremover.app.processing.internal.OrtSpectrogramModel
import com.musicremover.app.processing.internal.PcmFileReader
import com.musicremover.app.processing.internal.PcmFileWriter
import com.musicremover.app.processing.internal.PrimaryStemSink
import com.musicremover.app.processing.internal.VideoResultWriter
import com.musicremover.app.processing.internal.UrlNormalizer
import com.musicremover.app.processing.internal.VocalsSink
import com.musicremover.app.processing.internal.YtDlpDownloader
import com.musicremover.app.processing.internal.YtDlpErrors
import com.musicremover.app.processing.internal.YtDlpException
import com.musicremover.app.processing.internal.YtDlpInfo
import com.musicremover.app.processing.internal.CookieStore
import com.musicremover.app.processing.internal.withJobCookies
import com.musicremover.app.processing.internal.ytDlpErrorText
import com.musicremover.app.processing.internal.ytDlpVersionOrNull
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device separation of one local file or one link: model download, link download with yt-dlp
 * ([YtDlpDownloader]), decode, MDX-Net separation, AAC encode and mux, commit to [ResultCache], history
 * entry and notification. Runs as a dataSync foreground worker; all notifications go through
 * [NotificationHelper].
 */
class InternalProcessingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val ctx = context.applicationContext
    private val notif = NotificationHelper(ctx)
    private val cache = ResultCache(ctx)
    private val jobId = InternalJobEvents.PREFIX + id

    private val fileName = inputData.getString(InternalBackend.KEY_FILE_NAME) ?: "audio"
    private val modelName = inputData.getString(InternalBackend.KEY_MODEL) ?: ModelCatalog.HQ3
    private val sourceFileSize = inputData.getLong(InternalBackend.KEY_SOURCE_SIZE, -1L).takeIf { it >= 0 }
    private val sourceFilePath = inputData.getString(InternalBackend.KEY_SOURCE_PATH)
    /** Set for link jobs; normalized by [InternalBackend.startUrl]. */
    private val inputUrl = inputData.getString(InternalBackend.KEY_URL)

    private var lastStatus: String? = null
    private var lastProgress = -1

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(ctx.getString(R.string.starting), 0)

    override suspend fun doWork(): Result {
        // 0. Never separate without the foreground service
        try {
            setForeground(foregroundInfo(ctx.getString(R.string.starting), 0))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "$jobId: cannot start foreground service", e)
            return fail(ctx.getString(R.string.internal_needs_foreground))
        }

        return try {
            // 1. An earlier attempt was stopped after commit
            cache.getCachedFile(jobId)?.let { file ->
                val name = file.name.removePrefix("${jobId}_")
                finish(name, durationS = 0, info = null)
                return Result.success(workDataOf(InternalJobEvents.KEY_FILENAME to name))
            }
            // 1a. Bound reschedules after process death or system stops
            if (runAttemptCount >= MAX_ATTEMPTS) throw jobError(R.string.internal_interrupted)
            // 1b. Device preflight
            InternalCapability.current(ctx)?.let { throw InternalJobException(it) }
            // 1c. One separation at a time
            report(InternalJobEvents.STATUS_WAITING, 0)
            InternalProcessingLock.mutex.withLock { separate() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InternalJobException) {
            Log.w(TAG, "$jobId failed: ${e.message}", e.cause)
            fail(e.message ?: ctx.getString(R.string.internal_processing_failed))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "$jobId: out of memory", e)
            fail(ctx.getString(R.string.internal_out_of_memory))
        } catch (e: Exception) {
            Log.e(TAG, "$jobId failed", e)
            fail(ctx.getString(R.string.internal_processing_failed))
        }
    }

    /** Steps 2-8, under [InternalProcessingLock]. */
    private suspend fun separate(): Result {
        // Data may come from an older app version: validate the link again
        val url = inputUrl?.let { UrlNormalizer.normalize(it) ?: throw jobError(R.string.internal_url_invalid) }
        val uri = if (url != null) null else {
            inputData.getString(InternalBackend.KEY_URI)?.let(Uri::parse) ?: throw jobError(R.string.internal_bad_source)
        }
        // Progress bands: links 0-5 model, 5-15 download, 15-20 extract; files 0-10 model, 10-20 extract
        val modelEnd = if (url != null) 5 else 10
        val extractStart = if (url != null) 15 else 10
        val audioOnly = inputData.getBoolean(InternalBackend.KEY_AUDIO_ONLY, false)
        val bitrate = parseBitrate(inputData.getString(InternalBackend.KEY_BITRATE)) ?: run {
            Log.w(TAG, "$jobId: invalid bitrate, using 192k")
            DEFAULT_BITRATE
        }
        val spec = ModelCatalog.spec(ctx, modelName) ?: run {
            Log.e(TAG, "$jobId: model not available on-device")
            throw jobError(R.string.internal_processing_failed)
        }

        // 2. Fresh work dir
        val workDir = File(InternalBackend.workRoot(ctx), jobId)
        var model: OrtSpectrogramModel? = null
        var tmp: File? = null
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                workDir.deleteRecursively()
                if (!workDir.mkdirs()) throw IOException("cannot create work dir")
            }
            if (uri != null) {
                val readable = withContext(Dispatchers.IO) { InternalBackend.isReadableSource(ctx, uri) }
                if (!readable) throw jobError(R.string.internal_bad_source)
            }

            // 3. Model (0-10, links 0-5); before the link download so a missing model fails early
            val models = ModelManager.get(ctx)
            val modelFile = try {
                models.ensure(modelName) { bytes, total ->
                    report("downloading_model", if (total > 0) (bytes * modelEnd / total).toInt() else 0)
                }
            } catch (e: ModelStorageException) {
                throw InternalJobException(ctx.getString(R.string.internal_no_space, (e.neededBytes / 1_000_000).toInt()), e)
            } catch (e: ModelCorruptException) {
                Log.e(TAG, "$jobId: model verification failed", e)
                throw jobError(R.string.internal_model_corrupt, e)
            } catch (e: IOException) {
                throw InternalJobException(ctx.getString(R.string.internal_model_download_failed, e.message ?: ""), e)
            }

            // 3b. Link: info and download (5-15), inside the lock so the stream URLs are fresh
            var info: YtDlpInfo? = null
            val audioUri: Uri
            var videoUri: Uri? = null
            if (url != null) {
                report("downloading", modelEnd)
                val downloader = YtDlpDownloader(ctx)
                // A per-job copy of the imported cookies (yt-dlp rewrites it), deleted right after the download
                val media = withJobCookies(CookieStore(ctx), File(workDir, "cookies")) { cookies ->
                    val i = try {
                        downloader.info(url, "$jobId-info-$runAttemptCount", cookies)
                    } catch (e: YtDlpException) {
                        throw linkError(e, null, cookies != null)
                    }
                    if (i.isLive) {
                        Log.i(TAG, "$jobId: live stream refused")
                        throw jobError(R.string.internal_url_live)
                    }
                    info = i
                    try {
                        downloader.download(i, workDir, audioOnly, "$jobId-dl-$runAttemptCount", cookies) { p ->
                            report("downloading", modelEnd + p / 10)
                        }
                    } catch (e: YtDlpException) {
                        throw linkError(e, i, cookies != null)
                    }
                }
                audioUri = Uri.fromFile(media.audio)
                videoUri = media.video?.let(Uri::fromFile)
            } else {
                audioUri = uri!!
            }

            // 4. Decode (10-20, links 15-20)
            report("extracting", extractStart)
            val mixFile = File(workDir, "mix.f32")
            val decoded = AudioDecoder(ctx).decode(audioUri, mixFile, audioOnly, bitrate, videoUri) { p ->
                report("extracting", extractStart + p * (20 - extractStart) / 100)
            }

            // 4b. Free memory right before the session is created
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            if (mi.availMem < InternalCapability.MIN_AVAIL_MEM) {
                Log.w(TAG, "$jobId: availMem ${mi.availMem / 1_000_000} MB")
                throw jobError(R.string.internal_out_of_memory)
            }

            // 5. Separate (20-90)
            report("separating", 20)
            val ort = try {
                OrtSpectrogramModel(modelFile, spec.dimF, spec.dimT)
            } catch (e: OrtException) {
                Log.e(TAG, "$jobId: cannot load model", e)
                if (!models.delete(modelName)) modelFile.delete()
                throw jobError(R.string.internal_model_corrupt, e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "$jobId: model does not match its metadata", e)
                throw jobError(R.string.internal_model_mismatch, e)
            }
            model = ort
            val params = MdxParams(spec.nFft, spec.dimF, spec.dimT, spec.hop, spec.compensate.toFloat(), spec.overlap)
            val inputScale = if (decoded.peak > 0.9f) 0.9f / decoded.peak else 1f
            val vocalsFile = File(workDir, "vocals.f32")
            val peakVocals = try {
                withContext(Dispatchers.Default) {
                    PcmFileReader(mixFile).use { src ->
                        PcmFileReader(mixFile).use { mixForSink ->
                            PcmFileWriter(vocalsFile).use { writer ->
                                val sink = if (spec.primaryStem == "Vocals") {
                                    PrimaryStemSink(writer)
                                } else {
                                    VocalsSink(mixForSink, writer, inputScale, params.compensate)
                                }
                                MdxSeparator(params, ort).demix(src, inputScale, sink) { done, total ->
                                    report("separating", 20 + done * 70 / total)
                                }
                                if (sink is VocalsSink) sink.peakVocals else (sink as PrimaryStemSink).peak
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OrtException) {
                Log.e(TAG, "$jobId: ORT run failed", e)
                val oom = e.message?.contains("alloc", ignoreCase = true) == true
                throw jobError(if (oom) R.string.internal_out_of_memory else R.string.internal_processing_failed, e)
            } catch (e: IOException) {
                Log.w(TAG, "$jobId: write failed during separation", e)
                throw InternalJobException(ctx.getString(R.string.internal_no_space, neededMb(decoded.durationUs, bitrate)), e)
            }

            // 6. Encode + mux (90-99)
            report("merging", 90)
            val gain = if (peakVocals > 0.9f) 0.9f / peakVocals else 1f
            val stem = if (info != null) sanitizeName(info.videoInfo.title.ifBlank { "video" }) else sanitizeStem(fileName)
            val outName = if (decoded.videoTrack != null) "$stem-vocals-only.mp4" else "$stem-vocals-only.m4a"
            val out = withContext(Dispatchers.IO) { cache.tempFile(jobId) }
            tmp = out
            VideoResultWriter(ctx).write(
                pcm = vocalsFile,
                gain = gain,
                bitrateBps = bitrate,
                audioStartUs = decoded.audioStartUs,
                video = decoded.videoTrack,
                source = decoded.videoSource,
                out = out,
                workDir = workDir,
            ) { p -> report("merging", 90 + p * 9 / 100) }

            // 7. Commit; a finished job keeps its result even if cancelled now
            return withContext(NonCancellable + Dispatchers.IO) {
                cache.commit(jobId, outName, out) ?: run {
                    Log.e(TAG, "$jobId: commit failed")
                    throw jobError(R.string.internal_processing_failed)
                }
                committed = true
                finish(outName, durationS = (decoded.frames / AudioDecoder.SAMPLE_RATE).toInt(), info = info?.videoInfo)
                Result.success(workDataOf(InternalJobEvents.KEY_FILENAME to outName))
            }
        } finally {
            // 8. Cleanup; the lock is released only after the session is closed
            withContext(NonCancellable + Dispatchers.IO) {
                try { model?.close() } catch (e: Exception) { Log.w(TAG, "$jobId: close failed", e) }
                workDir.deleteRecursively()
                if (!committed) tmp?.takeIf { it.exists() }?.let { f ->
                    Log.w(TAG, "$jobId: deleting uncommitted output ${f.path} (${f.length()} bytes)")
                    f.delete()
                }
            }
        }
    }

    /**
     * History entry and done notification (the ViewModel may be gone). HistoryStore is first-writer-wins,
     * so link jobs are recorded as links here, with [info] on the normal path and without it on resume.
     */
    private fun finish(outName: String, durationS: Int, info: VideoInfo?) {
        HistoryStore(ctx).add(
            InternalHistory.item(
                jobId = jobId,
                model = modelName,
                outName = outName,
                durationS = durationS,
                url = inputUrl,
                info = info,
                fileName = fileName,
                sourceFileSize = sourceFileSize,
                sourceFilePath = sourceFilePath,
            )
        )
        notif.showDone(outName, jobId, cache.cachedUri(jobId)?.toString())
    }

    /**
     * Localized error for a failed link step; NO_SPACE names the MB from the info, if any. Reads the yt-dlp
     * version for the "Update yt-dlp" hint (cached after the first read).
     */
    private suspend fun linkError(e: YtDlpException, info: YtDlpInfo?, cookiesImported: Boolean): InternalJobException {
        val mb = YtDlpErrors.neededMb(info?.videoInfo?.duration ?: 0, info?.knownBytes)
        val version = if (e.kind == YtDlpErrors.Kind.NO_MEDIA) null else ytDlpVersionOrNull(ctx)
        return InternalJobException(ytDlpErrorText(ctx, e.kind, mb, cookiesImported, version, e.message.orEmpty()), e)
    }

    private fun fail(message: String): Result {
        notif.showError(message)
        return Result.failure(workDataOf(InternalJobEvents.KEY_ERROR to message))
    }

    /**
     * Throttled progress: published when the status changes or the value moves, both to WorkManager
     * (read by the ViewModel) and to the foreground notification.
     */
    @Synchronized
    private fun report(status: String, progress: Int) {
        if (isStopped) return
        if (status == lastStatus && progress == lastProgress) return
        lastStatus = status
        lastProgress = progress
        setProgressAsync(workDataOf(InternalJobEvents.KEY_STATUS to status, InternalJobEvents.KEY_PROGRESS to progress))
        notif.showProgress(statusLabel(ctx, status, null), progress)
    }

    private fun foregroundInfo(label: String, progress: Int): ForegroundInfo {
        val notification = notif.buildProgress(label, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationHelper.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun jobError(res: Int, cause: Throwable? = null) = InternalJobException(ctx.getString(res), cause)

    private fun neededMb(durationUs: Long, bitrate: Int): Int {
        val s = durationUs / 1_000_000.0
        return ((s * 705_600 + s * bitrate / 8) / 1_000_000 + 50).toInt()
    }

    companion object {
        private const val TAG = "MuremInternal"
        private const val MAX_ATTEMPTS = 3
        private const val DEFAULT_BITRATE = 192_000
        private val BITRATE = Regex("^(\\d{2,3})k$")
        private val UNSAFE_CHARS = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

        /** "192k" -> 192000 for 64..320 kbps, else null. */
        fun parseBitrate(value: String?): Int? {
            val kbps = value?.let { BITRATE.matchEntire(it) }?.groupValues?.get(1)?.toInt() ?: return null
            return if (kbps in 64..320) kbps * 1000 else null
        }

        /** [name] made safe for the result name, extension kept as text ("Mr. Smith" stays); "audio" if nothing is left. */
        fun sanitizeName(name: String): String =
            name.replace(UNSAFE_CHARS, "_").trim().take(100).ifEmpty { "audio" }

        /** File name without extension, safe for the result name; "audio" if nothing is left. */
        fun sanitizeStem(fileName: String): String = sanitizeName(fileName.substringBeforeLast('.'))
    }
}
