package com.musicremover.app.processing

import android.content.Context
import com.musicremover.app.R

/** User-facing label for a raw job status. Shared by the ViewModel and the on-device worker. */
fun statusLabel(context: Context, status: String, error: String?): String = when (status) {
    "queued", "waiting" -> context.getString(R.string.queued)
    "uploading" -> context.getString(R.string.uploading)
    "extracting" -> context.getString(R.string.extracting)
    "downloading" -> context.getString(R.string.downloading)
    "downloading_model" -> context.getString(R.string.downloading_model)
    "separating" -> context.getString(R.string.separating)
    "merging" -> context.getString(R.string.merging)
    "done" -> context.getString(R.string.vocals_extracted)
    "error" -> error ?: "Error"
    else -> status
}
