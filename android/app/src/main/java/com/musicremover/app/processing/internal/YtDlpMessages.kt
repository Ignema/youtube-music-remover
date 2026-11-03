package com.musicremover.app.processing.internal

import android.content.Context
import com.musicremover.app.R
import java.time.LocalDate

/**
 * Localized text for a yt-dlp failure, shared by the backend (preview) and the worker. [neededMb] is for
 * NO_SPACE; [version] is the installed yt-dlp version (null if unknown), used for the "Update yt-dlp" hint.
 * [stderr] is the yt-dlp stderr tail.
 */
fun ytDlpErrorText(
    context: Context,
    kind: YtDlpErrors.Kind,
    neededMb: Int,
    version: String? = null,
    stderr: String = "",
): String {
    val age = version?.let { YtDlpErrors.versionAgeDays(it, LocalDate.now()) }
    val m = YtDlpErrors.message(kind, age, stderr)
    val base = when (m.text) {
        YtDlpErrors.Text.INIT -> context.getString(R.string.internal_ytdlp_init_failed)
        YtDlpErrors.Text.NO_SPACE -> context.getString(R.string.internal_no_space, neededMb)
        YtDlpErrors.Text.UNSUPPORTED -> context.getString(R.string.internal_url_unsupported_site)
        YtDlpErrors.Text.UNAVAILABLE -> context.getString(R.string.internal_url_unavailable)
        YtDlpErrors.Text.BLOCKED -> context.getString(R.string.internal_url_blocked)
        YtDlpErrors.Text.NETWORK -> context.getString(R.string.internal_url_network)
        YtDlpErrors.Text.LIVE -> context.getString(R.string.internal_url_live)
        YtDlpErrors.Text.NO_AUDIO -> context.getString(R.string.internal_url_no_audio)
        YtDlpErrors.Text.LOGIN_REQUIRED -> context.getString(R.string.internal_url_login_required)
        YtDlpErrors.Text.DOWNLOAD_FAILED -> m.detail?.let { context.getString(R.string.internal_url_download_failed_detail, it) }
            ?: context.getString(R.string.internal_url_download_failed)
    }
    return if (m.updateHint && version != null) "$base ${context.getString(R.string.internal_ytdlp_outdated_hint, version)}" else base
}

/** The installed yt-dlp version for error texts, or null if it cannot be read. */
suspend fun ytDlpVersionOrNull(context: Context): String? = try {
    YtDlpRuntime.version(context).takeIf { it.isNotBlank() }
} catch (e: YtDlpException) {
    null
}
