package com.musicremover.app.processing.internal

/**
 * Validator for Netscape `cookies.txt` files (the format yt-dlp's `--cookies` reads). Only the cookie count
 * and domains leave this object; names and values never do. Pure.
 */
object CookiesTxt {
    const val MAX_BYTES = 1_048_576

    private const val HTTP_ONLY_PREFIX = "#HttpOnly_"
    private val HEADER = Regex("^#\\s*(Netscape )?HTTP Cookie File", RegexOption.IGNORE_CASE)
    private val BOOL = setOf("TRUE", "FALSE")
    private val EXPIRY = Regex("^-?\\d+(\\.\\d+)?$")
    private val DOMAIN = Regex("^\\.?[A-Za-z0-9.-]+$")

    /** What a valid file contains: [count] cookies for [domains] (sorted, without a leading dot). */
    data class Summary(val count: Int, val domains: List<String>)

    enum class Invalid { EMPTY, TOO_LARGE, BINARY, NOT_COOKIES_TXT, MALFORMED_LINE }

    sealed interface Result {
        data class Valid(val summary: Summary) : Result
        /** [line] is the 1-based line number for [Invalid.MALFORMED_LINE], else 0. */
        data class Rejected(val reason: Invalid, val line: Int = 0) : Result
    }

    /**
     * Valid when the file has a Netscape header or at least one cookie line, and every non-comment line
     * has 7 tab-separated fields (domain, TRUE/FALSE, path, TRUE/FALSE, expiry, name, value). [sizeBytes]
     * is the size read from disk, checked against [MAX_BYTES] before anything else.
     */
    fun validate(text: String, sizeBytes: Long = text.length.toLong()): Result {
        if (sizeBytes > MAX_BYTES) return Result.Rejected(Invalid.TOO_LARGE)
        if (text.isBlank()) return Result.Rejected(Invalid.EMPTY)
        if (text.contains('\u0000')) return Result.Rejected(Invalid.BINARY)
        var header = false
        val domains = sortedSetOf<String>()
        var count = 0
        for ((i, raw) in text.removePrefix("\uFEFF").lines().withIndex()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val cookieLine = when {
                line.startsWith(HTTP_ONLY_PREFIX) -> line.removePrefix(HTTP_ONLY_PREFIX)
                line.startsWith("#") -> {
                    if (HEADER.containsMatchIn(line)) header = true
                    continue
                }
                else -> line
            }
            val domain = cookieDomain(cookieLine) ?: return Result.Rejected(Invalid.MALFORMED_LINE, i + 1)
            domains += domain
            count++
        }
        if (count == 0) return Result.Rejected(if (header) Invalid.EMPTY else Invalid.NOT_COOKIES_TXT)
        return Result.Valid(Summary(count, domains.toList()))
    }

    /** The cookie values in a file that passed [validate], for scrubbing logs. Values shorter than 6 chars are skipped. */
    fun values(text: String): Set<String> = text.lines().mapNotNullTo(mutableSetOf()) { raw ->
        val line = raw.trimEnd('\r').removePrefix(HTTP_ONLY_PREFIX)
        if (line.startsWith("#")) return@mapNotNullTo null
        line.split('\t').takeIf { it.size == 7 }?.get(6)?.takeIf { it.length >= 6 }
    }

    private fun cookieDomain(line: String): String? {
        val f = line.split('\t')
        if (f.size != 7) return null
        if (!DOMAIN.matches(f[0]) || f[1] !in BOOL || !f[2].startsWith("/") || f[3] !in BOOL) return null
        if (!EXPIRY.matches(f[4]) || f[5].isEmpty()) return null
        return f[0].removePrefix(".").lowercase()
    }
}
