package com.musicremover.app.processing.internal

import android.content.Context
import com.musicremover.app.R

/** Localized text for a yt-dlp failure, shared by the backend (preview) and the worker. [neededMb] is for NO_SPACE. */
fun ytDlpKindText(context: Context, kind: YtDlpErrors.Kind, neededMb: Int): String = when (kind) {
    YtDlpErrors.Kind.INIT -> context.getString(R.string.internal_ytdlp_init_failed)
    YtDlpErrors.Kind.NO_SPACE -> context.getString(R.string.internal_no_space, neededMb)
    YtDlpErrors.Kind.UNSUPPORTED -> context.getString(R.string.internal_url_unsupported_site)
    YtDlpErrors.Kind.UNAVAILABLE -> context.getString(R.string.internal_url_unavailable)
    YtDlpErrors.Kind.BLOCKED -> context.getString(R.string.internal_url_blocked)
    YtDlpErrors.Kind.NETWORK -> context.getString(R.string.internal_url_network)
    YtDlpErrors.Kind.LIVE -> context.getString(R.string.internal_url_live)
    YtDlpErrors.Kind.FORMAT, YtDlpErrors.Kind.OTHER, YtDlpErrors.Kind.NO_MEDIA ->
        context.getString(R.string.internal_url_download_failed)
}
