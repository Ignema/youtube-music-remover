package com.musicremover.app.processing.internal

/**
 * Port of the server's `api/utils.py` `normalize_url`/`is_valid_url` for on-device links, stricter at the
 * end: input that is neither an http(s) URL nor a YouTube id is rejected instead of passed to yt-dlp.
 * Pure.
 */
object UrlNormalizer {
    const val MAX_LENGTH = 2048

    private val YT_ID = Regex("^[a-zA-Z0-9_-]{11}$")
    private val YT_PATTERNS = listOf(
        Regex("(?:https?://)?(?:www\\.)?youtu\\.be/([a-zA-Z0-9_-]{11})"),
        Regex("(?:https?://)?(?:www\\.)?youtube\\.com/watch\\?v=([a-zA-Z0-9_-]{11})"),
        Regex("(?:https?://)?(?:www\\.)?youtube\\.com/shorts/([a-zA-Z0-9_-]{11})"),
    )
    private val URL = Regex("^https?://[^\\s]+$")

    /** The URL to give yt-dlp, or null if [input] is empty, longer than 2048 chars or not a link. */
    fun normalize(input: String?): String? {
        val s = input?.trim().orEmpty()
        if (s.isEmpty() || s.length > MAX_LENGTH) return null
        val url = when {
            YT_ID.matches(s) -> watchUrl(s)
            URL.matches(s) -> s
            else -> YT_PATTERNS.firstNotNullOfOrNull { it.find(s)?.groupValues?.get(1) }?.let(::watchUrl)
        } ?: return null
        return url.takeIf { it.length <= MAX_LENGTH && URL.matches(it) }
    }

    private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
}
