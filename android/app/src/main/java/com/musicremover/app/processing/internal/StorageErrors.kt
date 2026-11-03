package com.musicremover.app.processing.internal

/**
 * Recognizes "disk full" failures. MediaMuxer write failures carry no errno text, so the text match is
 * only a secondary signal next to a low [java.io.File.getUsableSpace].
 */
object StorageErrors {
    private const val MAX_DEPTH = 10
    private const val LOW_SPACE_BYTES = 10L * 1024 * 1024

    /** True if any message in the cause chain (at most 10 levels) mentions ENOSPC. */
    fun isNoSpace(t: Throwable): Boolean {
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth < MAX_DEPTH) {
            if (c.message?.let(::isNoSpaceText) == true) return true
            c = c.cause.takeIf { it !== c }
            depth++
        }
        return false
    }

    /** True if [s] contains `ENOSPC` or `No space left on device`, ignoring case. */
    fun isNoSpaceText(s: String): Boolean =
        s.contains("ENOSPC", ignoreCase = true) || s.contains("No space left on device", ignoreCase = true)

    /** [isNoSpace], or less than 10 MB left in the work dir. */
    fun isLikelyNoSpace(t: Throwable, usableBytes: Long): Boolean = isNoSpace(t) || usableBytes < LOW_SPACE_BYTES
}
