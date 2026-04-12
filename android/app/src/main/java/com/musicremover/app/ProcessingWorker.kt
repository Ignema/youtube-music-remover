package com.musicremover.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.musicremover.app.data.ApiClient
import com.musicremover.app.data.NotificationHelper
import com.musicremover.app.data.ResultCache
import kotlinx.coroutines.delay

/**
 * WorkManager worker that polls job status and caches the result.
 * Survives app kills, device doze, and process death.
 */
class ProcessingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString("job_id") ?: return Result.failure()
        val serverUrl = inputData.getString("server_url") ?: return Result.failure()
        val filename = inputData.getString("filename") ?: "result.mp4"

        val notif = NotificationHelper(applicationContext)
        val cache = ResultCache(applicationContext)
        val api = ApiClient.getService(serverUrl)

        // Show as foreground work
        setForeground(ForegroundInfo(
            NotificationHelper.NOTIFICATION_ID,
            notif.buildProgress("Processing…", 0),
        ))

        var retries = 0
        while (true) {
            delay(2500)
            try {
                val status = api.status(jobId)
                retries = 0

                val text = when (status.status) {
                    "queued" -> "Queued…"
                    "uploading" -> "Uploading…"
                    "extracting" -> "Extracting audio…"
                    "downloading" -> "Downloading video…"
                    "separating" -> "Separating vocals…"
                    "merging" -> "Merging audio…"
                    "done" -> "Done"
                    "error" -> status.error ?: "Error"
                    else -> status.status
                }

                setForeground(ForegroundInfo(
                    NotificationHelper.NOTIFICATION_ID,
                    notif.buildProgress(text, status.progress),
                ))

                when (status.status) {
                    "done" -> {
                        val name = status.filename ?: filename
                        // Cache the result on-device
                        cache.cacheResult(serverUrl, jobId, name)
                        notif.showDone(name)

                        return Result.success(Data.Builder()
                            .putString("job_id", jobId)
                            .putString("filename", name)
                            .putString("status", "done")
                            .build())
                    }
                    "error" -> {
                        notif.showError(text)
                        return Result.failure(Data.Builder()
                            .putString("error", text)
                            .build())
                    }
                }
            } catch (_: Exception) {
                retries++
                if (retries >= 40) {
                    notif.showError("Lost connection to server")
                    return Result.retry()
                }
                delay(retries * 1000L)
            }
        }
    }
}
