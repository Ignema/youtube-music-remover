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
import com.musicremover.app.data.HistoryItem
import com.musicremover.app.data.HistoryStore
import com.musicremover.app.data.NotificationHelper
import com.musicremover.app.data.ResultCache
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
import com.musicremover.app.processing.internal.PrimarySink
import com.musicremover.app.processing.internal.VocalsEncoder
import com.musicremover.app.processing.internal.VocalsSink
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device separation of one local file: model download, decode, MDX-Net separation, AAC encode and
 * mux, commit to [ResultCache], history entry and notification. Runs as a dataSync foreground worker;
 * all notifications go through [NotificationHelper].
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
                finish(name, durationS = 0)
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
            Log.w(TAG, "$jobId failed", e.cause)
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
        val uri = inputData.getString(InternalBackend.KEY_URI)?.let(Uri::parse)
            ?: throw jobError(R.string.internal_bad_source)
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
            val readable = withContext(Dispatchers.IO) { InternalBackend.isReadableSource(ctx, uri) }
            if (!readable) throw jobError(R.string.internal_bad_source)

            // 3. Model (0-10)
            val models = ModelManager.get(ctx)
            val modelFile = try {
                models.ensure(modelName) { bytes, total ->
                    report("downloading_model", if (total > 0) (bytes * 10 / total).toInt() else 0)
                }
            } catch (e: ModelStorageException) {
                throw InternalJobException(ctx.getString(R.string.internal_no_space, (e.neededBytes / 1_000_000).toInt()), e)
            } catch (e: ModelCorruptException) {
                Log.e(TAG, "$jobId: model verification failed", e)
                throw jobError(R.string.internal_model_corrupt, e)
            } catch (e: IOException) {
                throw InternalJobException(ctx.getString(R.string.internal_model_download_failed, e.message ?: ""), e)
            }

            // 4. Decode (10-20)
            report("extracting", 10)
            val mixFile = File(workDir, "mix.f32")
            val decoded = AudioDecoder(ctx).decode(uri, mixFile, audioOnly, bitrate) { p ->
                report("extracting", 10 + p / 10)
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
            val stem = sanitizeStem(fileName)
            val outName = if (decoded.videoTrack != null) "$stem-vocals-only.mp4" else "$stem-vocals-only.m4a"
            val out = withContext(Dispatchers.IO) { cache.tempFile(jobId) }
            tmp = out
            VocalsEncoder(ctx).encode(
                pcm = vocalsFile,
                gain = gain,
                bitrateBps = bitrate,
                audioStartUs = decoded.audioStartUs,
                video = decoded.videoTrack,
                videoSource = if (decoded.videoTrack != null) uri else null,
                out = out,
            ) { p -> report("merging", 90 + p * 9 / 100) }

            // 7. Commit; a finished job keeps its result even if cancelled now
            return withContext(NonCancellable + Dispatchers.IO) {
                cache.commit(jobId, outName, out) ?: run {
                    Log.e(TAG, "$jobId: commit failed")
                    throw jobError(R.string.internal_processing_failed)
                }
                committed = true
                finish(outName, durationS = (decoded.frames / AudioDecoder.SAMPLE_RATE).toInt())
                Result.success(workDataOf(InternalJobEvents.KEY_FILENAME to outName))
            }
        } finally {
            // 8. Cleanup; the lock is released only after the session is closed
            withContext(NonCancellable + Dispatchers.IO) {
                try { model?.close() } catch (e: Exception) { Log.w(TAG, "$jobId: close failed", e) }
                workDir.deleteRecursively()
                if (!committed) tmp?.delete()
            }
        }
    }

    /** History entry and done notification (the ViewModel may be gone). HistoryStore deduplicates. */
    private fun finish(outName: String, durationS: Int) {
        HistoryStore(ctx).add(
            HistoryItem(
                filename = outName,
                url = "",
                model = modelName,
                jobId = jobId,
                isFileUpload = true,
                sourceFileName = fileName,
                sourceFileSize = sourceFileSize,
                sourceFilePath = sourceFilePath,
                ytTitle = fileName.substringBeforeLast('.'),
                ytDuration = durationS,
            )
        )
        notif.showDone(outName, jobId, cache.cachedUri(jobId)?.toString())
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

    /** Sink for a model whose primary stem is already the vocals. */
    private class PrimaryStemSink(private val out: PcmFileWriter) : PrimarySink {
        var peak = 0f
            private set

        override fun accept(pos: Long, primaryL: FloatArray, primaryR: FloatArray, count: Int) {
            var p = peak
            for (j in 0 until count) p = maxOf(p, abs(primaryL[j]), abs(primaryR[j]))
            peak = p
            out.write(primaryL, primaryR, 0, count)
        }
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

        /** File name without extension, safe for the result name; "audio" if nothing is left. */
        fun sanitizeStem(fileName: String): String =
            fileName.substringBeforeLast('.').replace(UNSAFE_CHARS, "_").trim().take(100).ifEmpty { "audio" }
    }
}
