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
