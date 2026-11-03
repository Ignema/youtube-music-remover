package com.musicremover.app.processing.internal

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A yt-dlp failure; [kind] picks the user-facing text, [message] is the stderr tail or a detail for the log. */
class YtDlpException(val kind: YtDlpErrors.Kind, detail: String, cause: Throwable? = null) : Exception(detail, cause)

/** Maps yt-dlp stderr to an error [Kind] and the user-facing [Text]. Pure. */
object YtDlpErrors {
    enum class Kind { INIT, NO_SPACE, UNSUPPORTED, UNAVAILABLE, BLOCKED, LOGIN_REQUIRED, NETWORK, FORMAT, OTHER, NO_MEDIA, LIVE }

    /** Which string the user sees; mapped to resources in `YtDlpMessages.kt`. */
    enum class Text {
        INIT, NO_SPACE, UNSUPPORTED, UNAVAILABLE, BLOCKED, NETWORK, LIVE, NO_AUDIO, DOWNLOAD_FAILED, LOGIN_REQUIRED,
    }

    /** [text] plus whether to append "tap Settings → Update yt-dlp" and the short yt-dlp [detail] (OTHER only). */
    data class Message(val text: Text, val updateHint: Boolean, val detail: String?)

    private const val PCM_BYTES_PER_SECOND = 705_600L
    private const val SPACE_MARGIN = 50L * 1024 * 1024
    private const val TAIL_CHARS = 2000
    private const val SUMMARY_CHARS = 160
    const val OUTDATED_DAYS = 14L

    // Ordered first-match list of case-insensitive literals. BLOCKED before NETWORK so a 403 page is BLOCKED.
    // LOGIN_REQUIRED after UNAVAILABLE and BLOCKED, so YouTube's private-video and "Sign in to confirm"
    // errors (which also mention --cookies) keep their kinds.
    private val RULES = listOf(
        Kind.UNSUPPORTED to listOf("Unsupported URL"),
        Kind.UNAVAILABLE to listOf("Video unavailable", "Private video", "This video is not available", "HTTP Error 404"),
        Kind.BLOCKED to listOf(
            "Sign in to confirm", "not a bot", "confirm your age", "age-restricted",
            "inappropriate for some users", "HTTP Error 403",
        ),
        Kind.LOGIN_REQUIRED to listOf(
            "login required", "rate-limit reached", "exceeded the rate-limit", "empty media response",
            "You need to log in", "locked behind the login page",
        ),
        Kind.NETWORK to listOf(
            "Unable to download webpage", "getaddrinfo", "timed out", "Connection refused",
            "Connection reset", "Network is unreachable",
        ),
        Kind.FORMAT to listOf("Requested format is not available"),
    )

    private val BOILERPLATE = listOf("; please report this issue on", " Confirm you are on the latest version", " Use --cookies")

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
     * What to show for [kind]. [versionAgeDays]: age of the installed yt-dlp, null if unknown. [stderr] gives
     * the detail for OTHER.
     */
    fun message(kind: Kind, versionAgeDays: Long?, stderr: String = ""): Message {
        val text = when (kind) {
            Kind.INIT -> Text.INIT
            Kind.NO_SPACE -> Text.NO_SPACE
            Kind.UNSUPPORTED -> Text.UNSUPPORTED
            Kind.UNAVAILABLE -> Text.UNAVAILABLE
            Kind.BLOCKED -> Text.BLOCKED
            Kind.LOGIN_REQUIRED -> Text.LOGIN_REQUIRED
            Kind.NETWORK -> Text.NETWORK
            Kind.LIVE -> Text.LIVE
            Kind.NO_MEDIA -> Text.NO_AUDIO
            Kind.FORMAT, Kind.OTHER -> Text.DOWNLOAD_FAILED
        }
        // Kinds where a newer extractor can help; the LOGIN_REQUIRED text already says so
        val canHelp = kind in setOf(Kind.UNSUPPORTED, Kind.UNAVAILABLE, Kind.NETWORK, Kind.FORMAT, Kind.OTHER)
        val outdated = versionAgeDays != null && versionAgeDays > OUTDATED_DAYS
        val detail = if (kind == Kind.OTHER) summary(stderr) else null
        return Message(text, canHelp && outdated, detail)
    }

    /** Days since the release date in a yt-dlp version (`2025.11.12`, nightly `2026.06.28.234618`), or null. */
    fun versionAgeDays(version: String, today: LocalDate): Long? {
        val m = Regex("^(\\d{4})\\.(\\d{1,2})\\.(\\d{1,2})").find(version.trim()) ?: return null
        val (y, mo, d) = m.destructured
        val date = try {
            LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
        } catch (e: java.time.DateTimeException) {
            return null
        }
        return ChronoUnit.DAYS.between(date, today)
    }

    /**
     * The last `ERROR:` line without the prefix and yt-dlp's boilerplate, at most 160 chars, or null.
     */
    fun summary(stderr: String): String? {
        var s = stderr.lines().lastOrNull { it.trimStart().startsWith("ERROR:") }?.trim()?.removePrefix("ERROR:")?.trim()
            ?: return null
        for (b in BOILERPLATE) {
            val i = s.indexOf(b)
            if (i >= 0) s = s.substring(0, i)
        }
        s = s.trim().trimEnd('.', ';', ',').trim()
        return s.takeIf { it.isNotEmpty() }?.take(SUMMARY_CHARS)
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
