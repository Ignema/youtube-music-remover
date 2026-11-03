package com.musicremover.app.processing.internal

/** A yt-dlp failure; [kind] picks the user-facing text, [message] is the stderr tail or a detail for the log. */
class YtDlpException(val kind: YtDlpErrors.Kind, detail: String, cause: Throwable? = null) : Exception(detail, cause)

/** Maps yt-dlp stderr to an error [Kind]. Pure. */
object YtDlpErrors {
    enum class Kind { INIT, NO_SPACE, UNSUPPORTED, UNAVAILABLE, BLOCKED, NETWORK, FORMAT, OTHER, NO_MEDIA, LIVE }

    private const val PCM_BYTES_PER_SECOND = 705_600L
    private const val SPACE_MARGIN = 50L * 1024 * 1024
    private const val TAIL_CHARS = 2000

    // Ordered first-match list of case-insensitive literals; BLOCKED before NETWORK so a 403 page is BLOCKED
    private val RULES = listOf(
        Kind.UNSUPPORTED to listOf("Unsupported URL"),
        Kind.UNAVAILABLE to listOf("Video unavailable", "Private video", "This video is not available", "HTTP Error 404"),
        Kind.BLOCKED to listOf(
            "Sign in to confirm", "not a bot", "confirm your age", "age-restricted",
            "inappropriate for some users", "HTTP Error 403",
        ),
        Kind.NETWORK to listOf(
            "Unable to download webpage", "getaddrinfo", "timed out", "Connection refused",
            "Connection reset", "Network is unreachable",
        ),
        Kind.FORMAT to listOf("Requested format is not available"),
    )

    /**
     * The kind for a failed `YoutubeDL.execute`. Callers pass `YoutubeDLException.message` from `execute`:
     * in youtubedl-android 0.18.1 that message is the process's full stderr. A `YoutubeDLException` from
     * init is [Kind.INIT] and never classified; `YoutubeDL.CanceledException` is handled as cancellation by
     * `runYtDlp`. INIT, NO_MEDIA and LIVE are decided by the caller, never here.
     */
    fun classify(stderr: String): Kind {
        if (StorageErrors.isNoSpaceText(stderr)) return Kind.NO_SPACE
        for ((kind, needles) in RULES) {
            if (needles.any { stderr.contains(it, ignoreCase = true) }) return kind
        }
        return Kind.OTHER
    }

    /**
     * MB to show in the "not enough storage" error of a link job: the two PCM files, the downloaded media
     * ([knownBytes], the sizes yt-dlp reports for the selected formats, if any) and a 50 MB margin.
     */
    fun neededMb(durationS: Int, knownBytes: Long?): Int =
        ((durationS * PCM_BYTES_PER_SECOND + (knownBytes ?: 0L) + SPACE_MARGIN) / 1_000_000).toInt()

    /** The last 2000 chars of [stderr], for the log. */
    fun tail(stderr: String): String = stderr.takeLast(TAIL_CHARS)
}
