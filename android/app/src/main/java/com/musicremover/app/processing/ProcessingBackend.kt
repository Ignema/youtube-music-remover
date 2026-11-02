package com.musicremover.app.processing

import android.net.Uri
import com.musicremover.app.data.VideoInfo
import kotlinx.coroutines.flow.Flow
import java.io.File

data class ProcessOptions(
    val model: String,
    val audioOnly: Boolean,
    val bitrate: String,
    val batchSize: Int = 4,
)

sealed interface JobEvent {
    /**
     * Raw backend status ("queued", "uploading", "extracting", "downloading", "downloading_model",
     * "separating", "merging", "done", "error", "cancelled", or unknown). Emitted on every poll,
     * including the terminal one, before [Done]/[Failed]. [filename] and [metadata] are set once the
     * status is "done", while the result is still being stored locally.
     */
    data class Progress(
        val status: String,
        val progress: Int,
        val error: String? = null,
        val filename: String? = null,
        val metadata: VideoInfo? = null,
    ) : JobEvent

    /** [file] is the result in ResultCache, or null if it could not be stored locally */
    data class Done(val file: File?, val filename: String, val metadata: VideoInfo?) : JobEvent

    /** [status] is "error" or "cancelled" */
    data class Failed(val status: String, val error: String?) : JobEvent

    /** The backend stopped answering while the job was running */
    data object ConnectionLost : JobEvent
}

/** Where vocal separation runs. Phase 1 only has the remote server (PC or Termux). */
interface ProcessingBackend {
    val id: String

    /** True if the backend can accept jobs. Never throws. */
    suspend fun isReady(): Boolean

    /** Video metadata for a URL. Throws on failure. */
    suspend fun info(url: String): VideoInfo

    fun supportedModels(): List<String>

    /** Start a URL job and return its job id. Throws on failure. */
    suspend fun startUrl(url: String, options: ProcessOptions): String

    /** Start a local file job and return its job id. Throws on failure. */
    suspend fun startFile(uri: Uri, fileName: String, options: ProcessOptions): String

    /** Job progress. Completes after a terminal event (Done, Failed or ConnectionLost). */
    fun events(jobId: String): Flow<JobEvent>

    /** Best effort, never throws. */
    suspend fun cancel(jobId: String)

    /** True if the job finished and its result can be fetched. Throws if the backend is unreachable. */
    suspend fun isResultAvailable(jobId: String): Boolean

    /** Store the job result in ResultCache. Returns null on failure. */
    suspend fun fetchResult(jobId: String, filename: String): File?

    /** Pre-computed waveform, or null if unavailable. Never throws. */
    suspend fun waveform(jobId: String): List<Float>?

    /** Remote download URL for fallback playback/share of uncached items; null for on-device backends. */
    fun remoteResultUrl(jobId: String): String?
}
