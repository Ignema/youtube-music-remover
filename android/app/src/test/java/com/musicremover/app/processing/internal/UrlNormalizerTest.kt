package com.musicremover.app.processing.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors the server's api/test_api.py cases, plus the on-device rejections. */
class UrlNormalizerTest {
    private val watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test
    fun bareIdBecomesWatchUrl() {
        assertEquals(watch, UrlNormalizer.normalize("dQw4w9WgXcQ"))
        assertEquals(watch, UrlNormalizer.normalize("  dQw4w9WgXcQ  "))
    }

    @Test
    fun httpsUrlsPassThroughUnchanged() {
        assertEquals(watch, UrlNormalizer.normalize(watch))
        assertEquals("https://youtu.be/dQw4w9WgXcQ", UrlNormalizer.normalize("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("https://www.youtube.com/shorts/dQw4w9WgXcQ", UrlNormalizer.normalize("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("$watch&t=42", UrlNormalizer.normalize("$watch&t=42"))
        assertEquals("http://example.com/a.mp4", UrlNormalizer.normalize(" http://example.com/a.mp4\n"))
    }

    @Test
    fun partialYouTubeUrlsBecomeWatchUrls() {
        assertEquals(watch, UrlNormalizer.normalize("youtu.be/dQw4w9WgXcQ"))
        assertEquals(watch, UrlNormalizer.normalize("www.youtube.com/watch?v=dQw4w9WgXcQ&t=42"))
        assertEquals(watch, UrlNormalizer.normalize("youtube.com/shorts/dQw4w9WgXcQ"))
    }

    @Test
    fun rejectsOptionsOtherSchemesAndJunk() {
        assertNull(UrlNormalizer.normalize("--exec x"))
        assertNull(UrlNormalizer.normalize("-o /sdcard/x"))
        assertNull(UrlNormalizer.normalize("file:///data/data/com.musicremover.app/files/x"))
        assertNull(UrlNormalizer.normalize("not a video"))
        assertNull(UrlNormalizer.normalize("ftp://example.com/a.mp4"))
        assertNull(UrlNormalizer.normalize("https://example.com/a b"))
    }

    @Test
    fun rejectsEmptyAndTooLong() {
        assertNull(UrlNormalizer.normalize(""))
        assertNull(UrlNormalizer.normalize("   "))
        assertNull(UrlNormalizer.normalize(null))
        val longUrl = "https://example.com/" + "a".repeat(UrlNormalizer.MAX_LENGTH)
        assertNull(UrlNormalizer.normalize(longUrl))
        val maxUrl = "https://example.com/".let { it + "a".repeat(UrlNormalizer.MAX_LENGTH - it.length) }
        assertEquals(maxUrl, UrlNormalizer.normalize(maxUrl))
    }
}
