package com.musicremover.app.processing.internal

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** A copy keeps the source SPS (csd-0), which locks in passthrough. */
    @Test fun h264IsCopied() {
        check("h264.mp4", expectedPlan = VideoCodecPolicy.Plan.PASSTHROUGH, expectedMime = "video/avc")
        val srcCsd = csd0(File(dir, "h264.mp4"))
        assertNotNull("source csd-0", srcCsd)
        assertArrayEquals(srcCsd, csd0(File(dir, OUT_NAME)))
    }

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

    /** A re-encode writes a new SPS: the x264 fixture is High profile, MediaCodec's output is not that stream. */
    @Test fun forcedTranscodeOfH264() {
        check("h264.mp4", expectedPlan = VideoCodecPolicy.Plan.PASSTHROUGH, expectedMime = "video/avc", forceTranscode = true)
        assertCsd0Differs("h264.mp4")
    }

    /** The transcode path keeps the source's portrait display (as metadata or baked into the pixels). */
    @Test fun forcedTranscodeKeepsRotation() {
        check("h264_rot90.mp4", expectedPlan = VideoCodecPolicy.Plan.PASSTHROUGH, expectedMime = "video/avc", forceTranscode = true)
        val f = videoFormat(File(dir, OUT_NAME))
        val w = f.getInteger(MediaFormat.KEY_WIDTH)
        val h = f.getInteger(MediaFormat.KEY_HEIGHT)
        val rotation = if (f.containsKey(MediaFormat.KEY_ROTATION)) VideoCodecPolicy.normalizeRotation(f.getInteger(MediaFormat.KEY_ROTATION)) else 0
        val (displayW, displayH) = if (rotation == 90 || rotation == 270) h to w else w to h
        assertTrue("display ${displayW}x$displayH (stored ${w}x$h, rotation $rotation) is not portrait", displayH > displayW)
        assertCsd0Differs("h264_rot90.mp4")
    }

    /**
     * Cancelling while Transformer writes the temporary H.264 file stops promptly, deletes the file, and
     * leaves the work dir usable for the next transcode. The ordering (Transformer.cancel() before the
     * delete) is guaranteed by VideoTranscoder; this checks a clean, prompt cancel.
     */
    @Test fun cancelDuringTranscodeIsClean() {
        val asset = "h264_long.mp4"
        runBlocking {
            val src = File(dir, asset)
            testCtx.assets.open(asset).use { input -> src.outputStream().use { input.copyTo(it) } }
            val mix = File(dir, "mix.f32")
            val decoded = AudioDecoder(target).decode(Uri.fromFile(src), mix, audioOnly = false, bitrateBps = 128_000) {}
            val work = File(dir, "work").apply { mkdirs() }
            val h264 = File(work, "video-h264.mp4")
            val thrown = AtomicReference<Throwable?>(null)
            val job = CoroutineScope(Dispatchers.Default).launch {
                try {
                    VideoResultWriter(target).write(
                        pcm = mix,
                        gain = 1f,
                        bitrateBps = 128_000,
                        audioStartUs = decoded.audioStartUs,
                        video = decoded.videoTrack!!,
                        source = Uri.fromFile(src),
                        out = File(dir, OUT_NAME),
                        workDir = work,
                        forceTranscode = true,
                    ) {}
                } catch (e: Throwable) {
                    thrown.set(e)
                    // Anything else is reported by the assertions below instead of crashing the runner
                    if (e is CancellationException) throw e
                }
            }
            withTimeout(10_000) {
                while (!h264.exists() && job.isActive) delay(10)
            }
            assertTrue("write ended before the transcode started: ${thrown.get()}", job.isActive && h264.exists())
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue("job not cancelled: ${thrown.get()}", job.isCancelled)
            assertTrue("expected a cancellation, got ${thrown.get()}", thrown.get() is CancellationException)
            assertFalse("temporary H.264 file left behind", h264.exists())
        }
        // The same work dir still works for a full forced transcode
        check(asset, expectedPlan = VideoCodecPolicy.Plan.PASSTHROUGH, expectedMime = "video/avc", forceTranscode = true)
    }

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

        val out = File(dir, OUT_NAME)
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

    /** The output of the last [check] re-encoded the video of [asset]: its csd-0 (SPS) is not the source's. */
    private fun assertCsd0Differs(asset: String) {
        val srcCsd = csd0(File(dir, asset))
        val outCsd = csd0(File(dir, OUT_NAME))
        assertNotNull("source csd-0", srcCsd)
        assertNotNull("output csd-0", outCsd)
        assertFalse("csd-0 equals the source's: the video was copied, not re-encoded", srcCsd!!.contentEquals(outCsd!!))
    }

    private fun videoFormat(file: File): MediaFormat {
        val x = MediaExtractor()
        try {
            x.setDataSource(file.path)
            for (i in 0 until x.trackCount) {
                val f = x.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) return f
            }
            throw AssertionError("no video track in ${file.name}")
        } finally {
            x.release()
        }
    }

    /** The video track's csd-0 (the H.264 SPS), or null if it has none. */
    private fun csd0(file: File): ByteArray? = videoFormat(file).getByteBuffer("csd-0")?.let { b ->
        val d = b.duplicate()
        ByteArray(d.remaining()).also { d.get(it) }
    }

    private companion object {
        const val OUT_NAME = "out.mp4"
    }
}
