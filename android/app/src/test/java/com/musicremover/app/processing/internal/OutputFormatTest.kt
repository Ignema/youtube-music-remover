package com.musicremover.app.processing.internal

import android.content.Context
import android.content.ContextWrapper
import com.musicremover.app.QueueItem
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OutputFormatTest {
    private val ctx: Context = ContextWrapper(null)

    @Before
    fun reset() = Mp3Support.resetForTest()

    private fun want(format: String, hasVideo: Boolean, probe: Boolean): Pair<Boolean, Int> = runBlocking {
        var calls = 0
        val r = Mp3Support.wantMp3(format, hasVideo) { calls++; probe }
        r to calls
    }

    @Test
    fun wantMp3DecisionTable() {
        assertEquals(true to 1, want("mp3", hasVideo = false, probe = true))
        // Probe false: the worker writes m4a
        assertEquals(false to 1, want("mp3", hasVideo = false, probe = false))
        // A video result is always mp4; no probe
        assertEquals(false to 0, want("mp3", hasVideo = true, probe = true))
        assertEquals(false to 0, want("m4a", hasVideo = false, probe = true))
        assertEquals(false to 0, want("m4a", hasVideo = true, probe = true))
    }

    @Test
    fun throwingProbesGiveFalseAndAreNotCached() = runBlocking {
        val errors = listOf(
            YtDlpException(YtDlpErrors.Kind.INIT, "unzip failed"),
            IOException("error=13, Permission denied"),
            SecurityException("denied"),
        )
        for (e in errors) {
            assertFalse(e.javaClass.simpleName, Mp3Support.available(ctx) { throw e })
        }
        // Nothing was cached: the next probe runs
        var calls = 0
        assertTrue(Mp3Support.available(ctx) { calls++; true })
        assertEquals(1, calls)
    }

    @Test
    fun falseIsNotCached() = runBlocking {
        var calls = 0
        assertFalse(Mp3Support.available(ctx) { calls++; false })
        assertFalse(Mp3Support.available(ctx) { calls++; false })
        assertEquals(2, calls)
    }

    @Test
    fun trueIsCached() = runBlocking {
        assertTrue(Mp3Support.available(ctx) { true })
        var called = false
        assertTrue(Mp3Support.available(ctx) { called = true; throw IOException("must not run") })
        assertFalse(called)
    }

    @Test
    fun cancellationPropagates() = runBlocking {
        try {
            Mp3Support.available(ctx) { throw CancellationException("cancelled") }
            fail("CancellationException swallowed")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun parseFindsLibmp3lameOnALaterLine() {
        val out = """
            Encoders:
             V..... = Video
             A..... = Audio
             ------
             V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
             A....D aac                  AAC (Advanced Audio Coding)
             A....D libmp3lame           libmp3lame MP3 (MPEG audio layer 3) (codec mp3)
             A....D libopus              libopus Opus (codec opus)
        """.trimIndent()
        assertTrue(Mp3Support.parse(out))
        assertTrue(Mp3Support.parse("x\n A....D libmp3lame           libmp3lame MP3 (MPEG audio layer 3) (codec mp3)"))
    }

    @Test
    fun parseRejectsOutputWithoutTheEncoder() {
        assertFalse(Mp3Support.parse("Encoders:\n A....D aac                  AAC (Advanced Audio Coding)\n"))
        assertFalse(Mp3Support.parse(""))
        // Only mentioned, not listed as an audio encoder
        assertFalse(Mp3Support.parse("configuration: --enable-libmp3lame\n V....D libmp3lamefake  x"))
    }

    @Test
    fun queueItemJsonRoundTripWithAndWithoutOutputFormat() {
        val mp3 = QueueItem(url = "https://example.com/v", model = ModelCatalog.HQ3, audioOnly = true, bitrate = "192k", outputFormat = "mp3")
        assertEquals(mp3, QueueItem.fromJsonMap(mp3.toJsonMap()))
        val legacy = mp3.toJsonMap() - "outputFormat"
        assertEquals("m4a", QueueItem.fromJsonMap(legacy).outputFormat)
        assertEquals(mp3.copy(outputFormat = "m4a"), QueueItem.fromJsonMap(legacy))
    }
}
