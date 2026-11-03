package com.musicremover.app.processing.internal

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Decodes each fixture in assets/ (see make-fixtures.ps1) and writes the result with the decoded mix
 * as the "vocals", then checks the MP4 has one video track and one AAC track.
 */
@RunWith(AndroidJUnit4::class)
class VideoResultWriterTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(target.cacheDir, "video-result-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun h264IsCopied() { check("h264.mp4", expectedPlan = VideoCodecPolicy.Plan.PASSTHROUGH, expectedMime = "video/avc") }

    @Test fun h264WithBFramesIsCopied() { check("h264_bframes.mp4", expectedPlan = VideoCodecPolicy.Plan.PASSTHROUGH, expectedMime = "video/avc") }

    @Test fun hevcIsCopied() { check("hevc.mp4", expectedPlan = VideoCodecPolicy.Plan.PASSTHROUGH, expectedMime = "video/hevc") }

    @Test fun av1IsCopiedFromApi34() {
        val api34 = Build.VERSION.SDK_INT >= 34
        check(
            "av1.mp4",
            expectedPlan = if (api34) VideoCodecPolicy.Plan.PASSTHROUGH else VideoCodecPolicy.Plan.TRANSCODE,
            expectedMime = if (api34) "video/av01" else "video/avc",
        )
    }

    @Test fun vp9IsTranscodedToH264() { check("vp9.mp4", expectedPlan = VideoCodecPolicy.Plan.TRANSCODE, expectedMime = "video/avc") }

    @Test fun forcedTranscodeOfH264() { check("h264.mp4", expectedPlan = VideoCodecPolicy.Plan.PASSTHROUGH, expectedMime = "video/avc", forceTranscode = true) }

    /** A copy that throws in MediaMuxer.addTrack (VP9 is not writable to MP4) falls back to H.264. */
    @Test fun failedCopyFallsBackToH264() {
        check("vp9.mp4", expectedPlan = VideoCodecPolicy.Plan.TRANSCODE, expectedMime = "video/avc") {
            VideoTrackInfo(it.index, it.format, it.mime, it.rotation, VideoCodecPolicy.Plan.PASSTHROUGH)
        }
    }

    @Test fun rotationIsKept() {
        val decoded = check("h264_rot90.mp4", expectedPlan = VideoCodecPolicy.Plan.PASSTHROUGH, expectedMime = "video/avc")
        val rotation = decoded.second
        assertNotEquals(0, decoded.first.videoTrack!!.rotation)
        assertEquals(decoded.first.videoTrack!!.rotation, rotation)
    }

    /** Returns the decode result and the output video's rotation. */
    private fun check(
        asset: String,
        expectedPlan: VideoCodecPolicy.Plan,
        expectedMime: String,
        forceTranscode: Boolean = false,
        override: (VideoTrackInfo) -> VideoTrackInfo = { it },
    ): Pair<DecodeResult, Int> = runBlocking {
        val src = File(dir, asset)
        testCtx.assets.open(asset).use { input -> src.outputStream().use { input.copyTo(it) } }
        val mix = File(dir, "mix.f32")
        val decoded = AudioDecoder(target).decode(Uri.fromFile(src), mix, audioOnly = false, bitrateBps = 128_000) {}
        assertNotNull("no video track in $asset", decoded.videoTrack)
        val video = decoded.videoTrack!!
        assertEquals(expectedPlan, video.plan)

        val out = File(dir, "out.mp4")
        val work = File(dir, "work").apply { mkdirs() }
        VideoResultWriter(target).write(
            pcm = mix,
            gain = 1f,
            bitrateBps = 128_000,
            audioStartUs = decoded.audioStartUs,
            video = override(video),
            source = Uri.fromFile(src),
            out = out,
            workDir = work,
            forceTranscode = forceTranscode,
        ) {}

        val x = MediaExtractor()
        try {
            x.setDataSource(out.path)
            val formats = (0 until x.trackCount).map { x.getTrackFormat(it) }
            val videos = formats.filter { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            val audios = formats.filter { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
            assertEquals("video tracks in $asset result", 1, videos.size)
            assertEquals("AAC tracks in $asset result", 1, audios.size)
            assertEquals(2, formats.size)
            val v = videos[0]
            assertEquals(expectedMime, v.getString(MediaFormat.KEY_MIME))
            assertTrue("video duration", v.getLong(MediaFormat.KEY_DURATION) > 1_000_000L)
            val rotation = if (v.containsKey(MediaFormat.KEY_ROTATION)) v.getInteger(MediaFormat.KEY_ROTATION) else 0
            decoded to rotation
        } finally {
            x.release()
        }
    }
}
