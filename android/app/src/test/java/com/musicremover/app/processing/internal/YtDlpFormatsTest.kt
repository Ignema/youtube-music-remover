package com.musicremover.app.processing.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class YtDlpFormatsTest {
    @Test
    fun videoSelectorPrefersH264Capped1080AndDownloadsAudioSeparately() {
        assertEquals("(bv*[vcodec^=avc1][height<=1080]/bv*[height<=1080]/bv*/b),(ba/b)", YtDlpFormats.selector(audioOnly = false))
    }

    @Test
    fun audioOnlySelectorFallsBackToMuxed() {
        assertEquals("ba/b", YtDlpFormats.selector(audioOnly = true))
    }
}
