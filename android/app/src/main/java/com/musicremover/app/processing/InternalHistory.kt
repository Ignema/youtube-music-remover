package com.musicremover.app.processing

import com.musicremover.app.data.HistoryItem
import com.musicremover.app.data.VideoInfo

/**
 * History entries written by [InternalProcessingWorker]. [com.musicremover.app.data.HistoryStore] is
 * first-writer-wins per job id, so a link job must be recorded as a link job on every path. Pure.
 */
object InternalHistory {
    private val RESULT_SUFFIXES = listOf("-vocals-only.mp4", "-vocals-only.m4a")

    /**
     * File job when [url] is null. Link job otherwise: with [info] from yt-dlp (the normal path), or without
     * it after a restart that found the committed result (title from [outName], duration 0 so the info sheet
     * fetches the details again).
     */
    fun item(
        jobId: String,
        model: String,
        outName: String,
        durationS: Int,
        url: String?,
        info: VideoInfo?,
        fileName: String,
        sourceFileSize: Long?,
        sourceFilePath: String?,
        startedAt: Long? = null,
    ): HistoryItem = when {
        url == null -> HistoryItem(
            filename = outName,
            url = "",
            model = model,
            jobId = jobId,
            isFileUpload = true,
            sourceFileName = fileName,
            sourceFileSize = sourceFileSize,
            sourceFilePath = sourceFilePath,
            ytTitle = fileName.substringBeforeLast('.'),
            ytDuration = durationS,
        )
        info == null -> HistoryItem(
            filename = outName,
            url = url,
            model = model,
            jobId = jobId,
            isFileUpload = false,
            sourceFileName = null,
            ytTitle = titleFromOutName(outName),
            ytDuration = 0,
        )
        else -> HistoryItem(
            filename = outName,
            url = url,
            model = model,
            jobId = jobId,
            isFileUpload = false,
            sourceFileName = null,
            ytTitle = info.title.ifBlank { titleFromOutName(outName) },
            ytChannel = info.channel,
            ytThumbnail = info.thumbnail,
            ytDuration = if (info.duration > 0) info.duration else durationS,
            ytViewCount = info.view_count,
            ytUploadDate = info.upload_date,
            ytDescription = info.description,
        )
    }.copy(processedBy = BackendProvider.MODE_INTERNAL, startedAt = startedAt)

    private fun titleFromOutName(outName: String): String =
        RESULT_SUFFIXES.fold(outName) { name, suffix -> name.removeSuffix(suffix) }
}
