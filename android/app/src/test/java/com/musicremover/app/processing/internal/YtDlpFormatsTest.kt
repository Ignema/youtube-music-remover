package com.musicremover.app.processing.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpFormatsTest {
    @Test
    fun videoSelectorPrefersH264ThenMuxedAndDownloadsAudioSeparately() {
        assertEquals(
            "(bv*[vcodec^=avc1][height<=1080]/b[height<=?1080]/bv*[height<=1080]/bv*/b),(ba/b)",
            YtDlpFormats.selector(audioOnly = false),
        )
    }

    /** YouTube always matches the first alternative, which is the whole old selector's first alternative. */
    @Test
    fun h264StaysFirstForYouTube() {
        assertTrue(YtDlpFormats.VIDEO.startsWith("(bv*[vcodec^=avc1][height<=1080]/"))
    }

    @Test
    fun audioOnlySelectorFallsBackToMuxed() {
        assertEquals("ba/b", YtDlpFormats.selector(audioOnly = true))
    }
}
