package com.musicremover.app.processing.internal

import com.musicremover.app.processing.internal.VideoCodecPolicy.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCodecPolicyTest {
    @Test
    fun baseCodecsPassThroughOnMinSdk() {
        for (m in listOf("video/avc", "video/hevc", "video/mp4v-es", "video/3gpp")) {
            assertTrue(m, VideoCodecPolicy.isPassthrough(m, 26))
        }
    }

    @Test
    fun av1PassesThroughFromApi34() {
        assertFalse(VideoCodecPolicy.isPassthrough("video/av01", 33))
        assertTrue(VideoCodecPolicy.isPassthrough("video/av01", 34))
        assertTrue(VideoCodecPolicy.isPassthrough("video/av01", 35))
        assertFalse("video/av01" in VideoCodecPolicy.passthroughMimes(26))
        assertTrue("video/av01" in VideoCodecPolicy.passthroughMimes(34))
    }

    @Test
    fun webmAndDolbyVisionNeverPassThrough() {
        for (sdk in listOf(26, 33, 34, 35, 36)) {
            for (m in listOf("video/x-vnd.on2.vp9", "video/x-vnd.on2.vp8", "video/dolby-vision", "video/mpeg2")) {
                assertFalse("$m @ $sdk", VideoCodecPolicy.isPassthrough(m, sdk))
            }
        }
    }

    @Test
    fun mimeCompareIgnoresCase() {
        assertTrue(VideoCodecPolicy.isPassthrough("VIDEO/AVC", 26))
    }

    @Test
    fun chooseVideoTrackPrefersCopyableTrack() {
        assertEquals(1, VideoCodecPolicy.chooseVideoTrack(listOf("video/dolby-vision", "video/hevc"), 35))
        assertEquals(2, VideoCodecPolicy.chooseVideoTrack(listOf("audio/mp4a-latm", "video/x-vnd.on2.vp9", "video/avc"), 26))
        assertEquals(0, VideoCodecPolicy.chooseVideoTrack(listOf("video/x-vnd.on2.vp9"), 35))
        assertEquals(1, VideoCodecPolicy.chooseVideoTrack(listOf("audio/mp4a-latm", "video/av01"), 33))
        assertEquals(-1, VideoCodecPolicy.chooseVideoTrack(listOf("audio/mp4a-latm", null), 35))
        assertEquals(-1, VideoCodecPolicy.chooseVideoTrack(emptyList(), 35))
    }

    @Test
    fun planMatrix() {
        assertEquals(Plan.TRANSCODE, VideoCodecPolicy.plan("video/x-vnd.on2.vp9", 35, hasDecoder = true))
        assertEquals(Plan.UNSUPPORTED, VideoCodecPolicy.plan("video/x-vnd.on2.vp9", 35, hasDecoder = false))
        assertEquals(Plan.TRANSCODE, VideoCodecPolicy.plan("video/av01", 33, hasDecoder = true))
        assertEquals(Plan.UNSUPPORTED, VideoCodecPolicy.plan("video/av01", 33, hasDecoder = false))
        assertEquals(Plan.PASSTHROUGH, VideoCodecPolicy.plan("video/av01", 34, hasDecoder = false))
        assertEquals(Plan.PASSTHROUGH, VideoCodecPolicy.plan("video/avc", 26, hasDecoder = false))
        assertEquals(Plan.TRANSCODE, VideoCodecPolicy.plan("video/dolby-vision", 35, hasDecoder = true))
    }

    @Test
    fun rotationIsNormalised() {
        assertEquals(0, VideoCodecPolicy.normalizeRotation(0))
        assertEquals(90, VideoCodecPolicy.normalizeRotation(90))
        assertEquals(270, VideoCodecPolicy.normalizeRotation(-90))
        assertEquals(90, VideoCodecPolicy.normalizeRotation(450))
        assertEquals(180, VideoCodecPolicy.normalizeRotation(-180))
        assertEquals(0, VideoCodecPolicy.normalizeRotation(360))
        assertEquals(0, VideoCodecPolicy.normalizeRotation(45))
    }

    @Test
    fun displayMimeIsSanitised() {
        assertEquals("video/x-vnd.on2.vp9", VideoCodecPolicy.displayMime("video/x-vnd.on2.vp9"))
        assertEquals("video/bavc/b", VideoCodecPolicy.displayMime("video/<b>avc</b>"))
        assertEquals("video/av01", VideoCodecPolicy.displayMime("VIDEO/AV01"))
        assertEquals("unknown", VideoCodecPolicy.displayMime(null))
        assertEquals("unknown", VideoCodecPolicy.displayMime(""))
        assertEquals("unknown", VideoCodecPolicy.displayMime("<>"))
        assertEquals(40, VideoCodecPolicy.displayMime("video/" + "a".repeat(100)).length)
    }

    @Test
    fun transcodeEndNeverNegative() {
        assertEquals(0L, VideoCodecPolicy.transcodeEndUs(-5L))
        assertEquals(2_000_000L, VideoCodecPolicy.transcodeEndUs(2_000_000L))
    }

    @Test
    fun failureStageMapping() {
        for (s in listOf("video-addTrack", "orientation", "mux-start", "video-read", "video-write", "mux-stop")) {
            assertTrue(s, VideoCodecPolicy.isVideoFailure(s, hasVideo = true))
            assertFalse(s, VideoCodecPolicy.isVideoFailure(s, hasVideo = false))
        }
        assertFalse(VideoCodecPolicy.isVideoFailure("audio", hasVideo = true))
    }
}
