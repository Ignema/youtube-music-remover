package com.musicremover.app.processing.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadedMediaTest {
    private fun probe(name: String, size: Long, audio: Boolean, video: Boolean) =
        DownloadedMedia.Probe(File(name), size, audio, video)

    @Test
    fun separateFiles() {
        val v = probe("137.mp4", 5_000, audio = false, video = true)
        val a = probe("140.m4a", 1_000, audio = true, video = false)
        val m = DownloadedMedia.choose(listOf(v, a), audioOnly = false)
        assertEquals(a.file, m.audio)
        assertEquals(v.file, m.video)
    }

    @Test
    fun muxedOnlyIsBothAudioAndVideo() {
        val muxed = probe("18.mp4", 3_000, audio = true, video = true)
        val m = DownloadedMedia.choose(listOf(muxed), audioOnly = false)
        assertEquals(muxed.file, m.audio)
        assertEquals(muxed.file, m.video)
    }

    @Test
    fun audioOnlyFilePreferredOverLargerMuxedFile() {
        val muxed = probe("18.mp4", 9_000, audio = true, video = true)
        val small = probe("140.m4a", 1_000, audio = true, video = false)
        val big = probe("251.webm", 2_000, audio = true, video = false)
        val m = DownloadedMedia.choose(listOf(muxed, small, big), audioOnly = false)
        assertEquals(big.file, m.audio)
        assertEquals(muxed.file, m.video)
    }

    @Test
    fun audioOnlyHasNoVideo() {
        val a = probe("140.m4a", 1_000, audio = true, video = false)
        val v = probe("137.mp4", 5_000, audio = false, video = true)
        val m = DownloadedMedia.choose(listOf(a, v), audioOnly = true)
        assertEquals(a.file, m.audio)
        assertNull(m.video)
    }

    @Test
    fun noAudioIsNoMedia() {
        for (probes in listOf(emptyList(), listOf(probe("137.mp4", 5_000, audio = false, video = true)))) {
            try {
                DownloadedMedia.choose(probes, audioOnly = false)
                throw AssertionError("expected NO_MEDIA for $probes")
            } catch (e: YtDlpException) {
                assertEquals(YtDlpErrors.Kind.NO_MEDIA, e.kind)
            }
        }
    }
}
