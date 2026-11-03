package com.musicremover.app.processing

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.musicremover.app.R
import com.musicremover.app.data.ResultCache
import com.musicremover.app.data.VideoInfo
import com.musicremover.app.processing.internal.InternalCapability
import com.musicremover.app.processing.internal.ModelCatalog
import com.musicremover.app.processing.internal.ModelManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext

/**
 * Awaits an enqueue; if the caller is cancelled during [await], runs [onCancel] (e.g. cancels the enqueued
 * work) in [NonCancellable] and rethrows, so a cancelled start never leaves an orphan worker.
 *
 * Rule for callers: only the blocking pre-checks run inside `withContext(Dispatchers.IO)`. The enqueue,
 * `awaitOrCancel` and the `return` of the job id run in the caller's context, and nothing suspends after
 * `awaitOrCancel` returns. Otherwise a cancel landing after [await] resumed would make `withContext` throw,
 * discard the id and skip [onCancel].
 */
internal suspend fun <T> awaitOrCancel(await: suspend () -> T, onCancel: suspend () -> Unit): T =
    try {
        await()
    } catch (e: CancellationException) {
        withContext(NonCancellable) { onCancel() }
        throw e
    }

/** Runs jobs on this phone in [InternalProcessingWorker] (local files only). Job ids are `"local-" + work id`. */
class InternalBackend(context: Context, private val cache: ResultCache) : ProcessingBackend {
    private val appContext = context.applicationContext
    private val workManager get() = WorkManager.getInstance(appContext)

    override val id = "internal"

    override val managesNotifications = true

    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (InternalCapability.current(appContext) != null) return@withContext false
            if (ModelManager.get(appContext).isDownloaded(ModelCatalog.HQ3)) return@withContext true
            val cm = appContext.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: Exception) {
            false
        }
    }

    /** Localized reason why this phone cannot run on-device processing, or null. */
    fun unsupportedReason(): String? = InternalCapability.current(appContext)

    override suspend fun info(url: String): VideoInfo =
        throw UnsupportedOperationException(appContext.getString(R.string.internal_url_unsupported))

    override fun supportedModels(): List<String> = ModelCatalog.get(appContext).map { it.name }

    override suspend fun startUrl(url: String, options: ProcessOptions): String =
        throw UnsupportedOperationException(appContext.getString(R.string.internal_url_unsupported))

    override suspend fun startFile(uri: Uri, fileName: String, options: ProcessOptions): String {
        unsupportedReason()?.let { throw IllegalArgumentException(it) }
        val readable = withContext(Dispatchers.IO) { isReadableSource(appContext, uri) }
        if (!readable) throw IllegalArgumentException(appContext.getString(R.string.internal_bad_source))
        if (options.model !in supportedModels()) {
            Log.e(TAG, "Model not available on-device")
            throw IllegalArgumentException("Model not available on-device")
        }
        val input = Data.Builder()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_FILE_NAME, fileName)
            .putString(KEY_MODEL, options.model)
            .putBoolean(KEY_AUDIO_ONLY, options.audioOnly)
            .putString(KEY_BITRATE, options.bitrate)
            .putLong(KEY_SOURCE_SIZE, options.sourceFileSize ?: -1L)
            .apply { options.sourceFilePath?.let { putString(KEY_SOURCE_PATH, it) } }
            .build()
        val req = OneTimeWorkRequestBuilder<InternalProcessingWorker>()
            .setInputData(input)
            .addTag(WORK_TAG)
            .build()
        // Await the enqueue so the WorkInfo row exists before events() is collected
        val op = workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        awaitOrCancel({ op.await() }, { workManager.cancelWorkById(req.id).await() })
        return InternalJobEvents.PREFIX + req.id
    }

    override fun events(jobId: String): Flow<JobEvent> {
        val notFound = appContext.getString(R.string.internal_job_not_found)
        val uuid = InternalJobEvents.parseId(jobId) ?: return flowOf(JobEvent.Failed("error", notFound))
        return workManager.getWorkInfoByIdFlow(uuid)
            .transform { info ->
                val cached = withContext(Dispatchers.IO) { cache.getCachedFile(jobId) }
                InternalJobEvents.map(info, jobId, cached, notFound).forEach { emit(it) }
            }
            .distinctUntilChanged()
            .transformWhile { e ->
                emit(e)
                !InternalJobEvents.isTerminal(e)
            }
    }

    /** Awaits the cancel, so the work is CANCELLED in the database when this returns. Never throws. */
    override suspend fun cancel(jobId: String) {
        val uuid = InternalJobEvents.parseId(jobId) ?: return
        try {
            workManager.cancelWorkById(uuid).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cancel failed for $jobId", e)
        }
    }

    override suspend fun isResultAvailable(jobId: String): Boolean =
        withContext(Dispatchers.IO) { cache.getCachedFile(jobId) != null }

    override suspend fun fetchResult(jobId: String, filename: String): File? =
        withContext(Dispatchers.IO) { cache.getCachedFile(jobId) }

    /** The player decodes the local file itself. */
    override suspend fun waveform(jobId: String): List<Float>? = null

    override fun remoteResultUrl(jobId: String): String? = null

    /** Deletes worker scratch dirs (filesDir/work/<jobId>) of jobs not in [keep]. */
    fun cleanStaleWork(keep: Set<String>) {
        workRoot(appContext).listFiles()?.forEach { dir ->
            if (dir.name !in keep) dir.deleteRecursively()
        }
    }

    companion object {
        const val WORK_TAG = "internal"
        const val UNIQUE_WORK = "internal-processing"

        // Worker input Data keys
        const val KEY_URI = "uri"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_MODEL = "model"
        const val KEY_AUDIO_ONLY = "audioOnly"
        const val KEY_BITRATE = "bitrate"
        const val KEY_SOURCE_SIZE = "sourceFileSize"
        const val KEY_SOURCE_PATH = "sourceFilePath"

        private const val TAG = "MuremInternal"

        fun workRoot(context: Context): File = File(context.filesDir, "work")

        /**
         * True for a `content` URI, or a `file` URI under cacheDir/filesDir, that can be opened.
         * Blocking.
         */
        fun isReadableSource(context: Context, uri: Uri): Boolean {
            when (uri.scheme) {
                ContentResolver.SCHEME_CONTENT -> {}
                ContentResolver.SCHEME_FILE -> {
                    val path = uri.path ?: return false
                    val canonical = try { File(path).canonicalFile } catch (_: Exception) { return false }
                    val roots = listOf(context.cacheDir, context.filesDir).map { it.canonicalFile }
                    if (roots.none { canonical.path.startsWith(it.path + File.separator) }) return false
                }
                else -> return false
            }
            return try {
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            } catch (_: Exception) {
                false
            }
        }
    }
}
