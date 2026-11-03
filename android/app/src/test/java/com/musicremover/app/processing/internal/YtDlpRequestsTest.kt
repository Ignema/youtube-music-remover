package com.musicremover.app.processing.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpRequestsTest {
    private val work = File("work")
    private val infoJson = File(work, "info.json")
    private val outDir = File(work, "dl")

    /** "a b" appears as consecutive arguments. */
    private fun List<String>.hasPair(a: String, b: String) = windowed(2).any { it == listOf(a, b) }

    @Test
    fun infoArgsDumpJsonAndEndWithSeparatorThenUrl() {
        val url = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        val args = YtDlpRequests.infoArgs(url)
        assertTrue("--dump-json" in args)
        assertTrue("--no-playlist" in args)
        assertTrue(args.hasPair("--playlist-items", "1"))
        assertTrue("--ignore-config" in args)
        assertTrue("--no-warnings" in args)
        assertEquals(listOf("--", url), args.takeLast(2))
        assertEquals(1, args.count { it == "--" })
    }

    @Test
    fun downloadArgsNeverRunFfmpegAndEndWithSeparator() {
        val args = YtDlpRequests.downloadArgs(infoJson, outDir, audioOnly = false)
        assertTrue(args.hasPair("--fixup", "never"))
        assertTrue(args.hasPair("--downloader", "native"))
        assertTrue(args.hasPair("--load-info-json", infoJson.path))
        assertTrue(args.hasPair("-o", File(outDir, "%(format_id)s.%(ext)s").path))
        assertTrue(args.hasPair("-f", YtDlpFormats.VIDEO))
        assertTrue("--no-playlist" in args)
        assertTrue("--no-mtime" in args)
        assertTrue("--ignore-config" in args)
        assertFalse("--merge-output-format" in args)
        assertEquals("--", args.last())
    }

    @Test
    fun downloadArgsAudioOnlySelector() {
        val args = YtDlpRequests.downloadArgs(infoJson, outDir, audioOnly = true)
        assertTrue(args.hasPair("-f", YtDlpFormats.AUDIO_ONLY))
        assertEquals("--", args.last())
    }
}
