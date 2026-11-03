package com.musicremover.app.processing.internal

import com.musicremover.app.processing.internal.YtDlpErrors.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class YtDlpErrorsTest {
    private fun kind(stderr: String) = YtDlpErrors.classify(stderr)

    @Test
    fun oneSamplePerKind() {
        assertEquals(Kind.NO_SPACE, kind("ERROR: unable to write data: [Errno 28] No space left on device"))
        assertEquals(Kind.UNSUPPORTED, kind("ERROR: Unsupported URL: https://example.com/page"))
        assertEquals(Kind.UNAVAILABLE, kind("ERROR: [youtube] abc: Video unavailable. This video has been removed"))
        assertEquals(Kind.UNAVAILABLE, kind("ERROR: [youtube] abc: Private video. Sign in if you've been granted access"))
        assertEquals(Kind.UNAVAILABLE, kind("ERROR: unable to download video data: HTTP Error 404: Not Found"))
        assertEquals(Kind.BLOCKED, kind("ERROR: [youtube] abc: Sign in to confirm you're not a bot."))
        assertEquals(Kind.BLOCKED, kind("ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate for some users."))
        assertEquals(Kind.NETWORK, kind("ERROR: [generic] Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname> (caused by getaddrinfo failed)"))
        assertEquals(Kind.NETWORK, kind("ERROR: Connection reset by peer"))
        assertEquals(Kind.FORMAT, kind("ERROR: [youtube] abc: Requested format is not available. Use --list-formats"))
        assertEquals(Kind.OTHER, kind("ERROR: something unexpected"))
        assertEquals(Kind.OTHER, kind(""))
    }

    // Exact stderr from the diagnosis runs (yt-dlp 2025.11.12 and 2026.10.26)
    private val oldInstagram = """
        WARNING: [Instagram] DaI91choaNb: General metadata extraction failed (some metadata might be missing).
        WARNING: [Instagram] Main webpage is locked behind the login page. Retrying with embed webpage (some metadata might be missing).
        ERROR: [Instagram] DaI91choaNb: Requested content is not available, rate-limit reached or login required. Use --cookies-from-browser or --cookies for the authentication. See  https://github.com/yt-dlp/yt-dlp/wiki/FAQ#how-do-i-pass-cookies-to-yt-dlp  for how to manually pass cookies
    """.trimIndent()
    private val newInstagram = """
        WARNING: [Instagram] DMNvOGvIzEU: Instagram API is not granting access
        ERROR: [Instagram] DMNvOGvIzEU: Instagram sent an empty media response. Check if this post is accessible in your browser without being logged-in. If it is not, then use --cookies-from-browser or --cookies for the authentication.
    """.trimIndent()

    @Test
    fun instagramLoginAndRateLimitAreLoginRequired() {
        assertEquals(Kind.LOGIN_REQUIRED, kind(oldInstagram))
        assertEquals(Kind.LOGIN_REQUIRED, kind(newInstagram))
        assertEquals(Kind.LOGIN_REQUIRED, kind("ERROR: [instagram:story] 1: You need to log in to access this content"))
        assertEquals(Kind.LOGIN_REQUIRED, kind("ERROR: [Instagram] x: You have exceeded the rate-limit for accessing posts anonymously"))
    }

    /** YouTube errors that also mention --cookies keep their old kinds. */
    @Test
    fun youTubeCookieHintsKeepTheirKinds() {
        assertEquals(Kind.BLOCKED, kind("ERROR: [youtube] abc: Sign in to confirm you're not a bot. Use --cookies-from-browser or --cookies for the authentication."))
        assertEquals(Kind.UNAVAILABLE, kind("ERROR: [youtube] abc: Private video. Sign in if you've been granted access to this video. Use --cookies-from-browser or --cookies for the authentication."))
        assertEquals(Kind.BLOCKED, kind("ERROR: [youtube] abc: Sign in to confirm your age. Use --cookies-from-browser or --cookies"))
    }

    @Test
    fun emulatorTruncationIsNetwork() {
        assertEquals(Kind.NETWORK, kind("ERROR: [Instagram] DaI91choaNb: Unable to download webpage: 134471 bytes read (caused by <IncompleteRead: 134471 bytes read>)"))
        assertEquals(Kind.NETWORK, kind("ERROR: [Instagram] DaI91choaNb: Unable to download webpage: [SSL] record layer failure (_ssl.c:2580)"))
    }

    @Test
    fun loginTextDependsOnCookies() {
        assertEquals(YtDlpErrors.Text.LOGIN_NO_COOKIES, YtDlpErrors.message(Kind.LOGIN_REQUIRED, false, 1).text)
        assertEquals(YtDlpErrors.Text.LOGIN_COOKIES_FAILED, YtDlpErrors.message(Kind.LOGIN_REQUIRED, true, 1).text)
        // The no-cookies text already says "update yt-dlp"
        assertEquals(false, YtDlpErrors.message(Kind.LOGIN_REQUIRED, false, 400).updateHint)
        assertEquals(true, YtDlpErrors.message(Kind.LOGIN_REQUIRED, true, 400).updateHint)
    }

    @Test
    fun updateHintOnlyWhenOlderThan14Days() {
        assertEquals(false, YtDlpErrors.message(Kind.OTHER, false, 14).updateHint)
        assertEquals(true, YtDlpErrors.message(Kind.OTHER, false, 15).updateHint)
        assertEquals(false, YtDlpErrors.message(Kind.OTHER, false, null).updateHint)
        assertEquals(true, YtDlpErrors.message(Kind.UNSUPPORTED, false, 30).updateHint)
        assertEquals(true, YtDlpErrors.message(Kind.NETWORK, false, 30).updateHint)
        for (k in listOf(Kind.NO_SPACE, Kind.INIT, Kind.LIVE, Kind.NO_MEDIA, Kind.BLOCKED)) {
            assertEquals("$k", false, YtDlpErrors.message(k, false, 400).updateHint)
        }
    }

    @Test
    fun noMediaIsNoAudioText() {
        assertEquals(YtDlpErrors.Text.NO_AUDIO, YtDlpErrors.message(Kind.NO_MEDIA, false, null).text)
    }

    @Test
    fun otherCarriesShortDetail() {
        val stderr = "WARNING: x\nERROR: [Foo] 12: Something broke; please report this issue on  https://github.com/yt-dlp/yt-dlp/issues?q= , filling out the appropriate issue template. Confirm you are on the latest version using  yt-dlp -U"
        assertEquals("[Foo] 12: Something broke", YtDlpErrors.message(Kind.OTHER, false, null, stderr).detail)
        assertEquals(null, YtDlpErrors.message(Kind.NETWORK, false, null, stderr).detail)
        assertEquals(null, YtDlpErrors.summary("no error line"))
        assertEquals(160, YtDlpErrors.summary("ERROR: " + "x".repeat(500))!!.length)
    }

    @Test
    fun versionAge() {
        val today = java.time.LocalDate.of(2026, 11, 3)
        assertEquals(356L, YtDlpErrors.versionAgeDays("2025.11.12", today))
        assertEquals(8L, YtDlpErrors.versionAgeDays("2026.10.26", today))
        assertEquals(128L, YtDlpErrors.versionAgeDays("2026.06.28.234618", today))
        assertEquals(null, YtDlpErrors.versionAgeDays("?", today))
        assertEquals(null, YtDlpErrors.versionAgeDays("2026.13.40", today))
    }

    @Test
    fun redactRemovesCookieHeadersAndValues() {
        val s = "[debug] Cookie: sessionid=abc123456; csrftoken=zzz\nERROR: failed for SECRETVALUE99 here\nset-cookie=foo"
        val r = YtDlpErrors.redact(s, setOf("SECRETVALUE99"))
        assertEquals(false, r.contains("abc123456"))
        assertEquals(false, r.contains("SECRETVALUE99"))
        assertEquals(false, r.contains("foo"))
        assertEquals(true, r.contains("ERROR: failed for <redacted> here"))
    }

    @Test
    fun http403IsBlockedNotNetwork() {
        assertEquals(Kind.BLOCKED, kind("ERROR: Unable to download webpage: HTTP Error 403: Forbidden"))
    }

    @Test
    fun timedOutIsNetwork() {
        assertEquals(Kind.NETWORK, kind("ERROR: Unable to download webpage: <urlopen error timed out>"))
    }

    @Test
    fun bareUnableToDownloadWebpageIsNetwork() {
        assertEquals(Kind.NETWORK, kind("ERROR: [generic] Unable to download webpage: The read operation failed"))
    }

    @Test
    fun noBareWordMatches() {
        // "webpage" and "message" contain "age"; neither may become BLOCKED
        assertEquals(Kind.OTHER, kind("ERROR: the webpage returned an unknown message"))
    }

    @Test
    fun caseInsensitive() {
        assertEquals(Kind.UNSUPPORTED, kind("error: unsupported url: x"))
    }

    @Test
    fun neededMbWithAndWithoutKnownBytes() {
        // 19 s: 19 * 705_600 = 13_406_400; + 50 MiB (52_428_800) = 65_835_200 -> 65
        assertEquals(65, YtDlpErrors.neededMb(19, null))
        // + 1_000_000 known bytes -> 66_835_200 -> 66
        assertEquals(66, YtDlpErrors.neededMb(19, 1_000_000L))
        assertEquals(52, YtDlpErrors.neededMb(0, null))
    }

    @Test
    fun tailKeepsLast2000Chars() {
        val s = "a".repeat(3000) + "END"
        assertEquals(2000, YtDlpErrors.tail(s).length)
        assertEquals(true, YtDlpErrors.tail(s).endsWith("END"))
    }
}
