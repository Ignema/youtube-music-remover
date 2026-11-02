package com.musicremover.app.processing

import androidx.work.WorkInfo
import java.io.File
import java.util.UUID

/** Pure mapping from WorkManager state to [JobEvent]s for on-device jobs (`"local-" + work id`). */
object InternalJobEvents {
    const val PREFIX = "local-"

    // Worker Data keys
    const val KEY_STATUS = "status"
    const val KEY_PROGRESS = "progress"
    const val KEY_FILENAME = "filename"
    const val KEY_ERROR = "error"

    /** Status the worker reports while waiting for [InternalProcessingLock]; shown as queued. */
    const val STATUS_WAITING = "waiting"

    /** The WorkManager id of an on-device job, or null if [jobId] is not a valid one. Never throws. */
    fun parseId(jobId: String): UUID? {
        if (!jobId.startsWith(PREFIX)) return null
        return try {
            UUID.fromString(jobId.removePrefix(PREFIX))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Events for one WorkInfo emission. [cachedFile] is the committed result of [jobId] (or null).
     * A null [info] means a previously enqueued id that WorkManager has since pruned (resume path).
     */
    fun map(info: WorkInfo?, jobId: String, cachedFile: File?, notFoundMessage: String): List<JobEvent> {
        if (info == null) {
            if (cachedFile == null) return listOf(JobEvent.Failed("error", notFoundMessage))
            val name = cachedFile.name.removePrefix("${jobId}_")
            return listOf(JobEvent.Progress("done", 100, filename = name), JobEvent.Done(cachedFile, name, null))
        }
        return when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> listOf(JobEvent.Progress("queued", 0))
            WorkInfo.State.RUNNING -> {
                val status = info.progress.getString(KEY_STATUS)
                if (status == STATUS_WAITING) {
                    listOf(JobEvent.Progress("queued", 0))
                } else {
                    listOf(JobEvent.Progress(status ?: "extracting", info.progress.getInt(KEY_PROGRESS, 0)))
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                val name = info.outputData.getString(KEY_FILENAME)
                    ?: cachedFile?.name?.removePrefix("${jobId}_")
                    ?: "Done"
                listOf(JobEvent.Progress("done", 100, filename = name), JobEvent.Done(cachedFile, name, null))
            }
            WorkInfo.State.FAILED -> listOf(JobEvent.Failed("error", info.outputData.getString(KEY_ERROR)))
            WorkInfo.State.CANCELLED -> listOf(JobEvent.Failed("cancelled", null))
        }
    }

    fun isTerminal(e: JobEvent): Boolean = e !is JobEvent.Progress
}
