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

/** Which ETA text applies; [EtaText.MINUTES] carries the rounded minute count. */
enum class EtaText { MINUTES, ONE_MINUTE, UNDER_A_MINUTE }

/** Pure ETA wording: ≥ 90 s "about N min" (N = round(sec/60)), ≥ 45 s "about 1 min", else "less than a minute". */
fun etaLabel(sec: Int, text: (EtaText, Int) -> String): String = when {
    sec >= 90 -> text(EtaText.MINUTES, Math.round(sec / 60.0).toInt())
    sec >= 45 -> text(EtaText.ONE_MINUTE, 1)
    else -> text(EtaText.UNDER_A_MINUTE, 0)
}

fun etaLabel(context: Context, sec: Int): String = etaLabel(sec) { kind, n ->
    when (kind) {
        EtaText.MINUTES -> context.resources.getQuantityString(R.plurals.about_n_min_left, n, n)
        EtaText.ONE_MINUTE -> context.getString(R.string.about_1_min_left)
        EtaText.UNDER_A_MINUTE -> context.getString(R.string.less_than_minute_left)
    }
}

/** "Separating… · about 12 min left" while separating with a known ETA, else the plain label. */
fun statusWithEta(context: Context, status: String, error: String?, etaSec: Int?): String {
    val label = statusLabel(context, status, error)
    return if (status == "separating" && etaSec != null && etaSec >= 0) "$label · ${etaLabel(context, etaSec)}" else label
}
