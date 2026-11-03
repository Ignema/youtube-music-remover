package com.musicremover.app.processing.internal

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicremover.app.processing.internal.YtDlpTestSupport.TAG
import com.musicremover.app.processing.internal.YtDlpTestSupport.trackMimes
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real bundled yt-dlp against "Me at the zoo" (19 s), or the `muremTestUrl` argument. Needs internet;
 * without it every test is skipped. See [YtDlpTestSupport] for the arguments.
 */
@RunWith(AndroidJUnit4::class)
class YtDlpDownloaderTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val url = YtDlpTestSupport.url
    private lateinit var work: File

    @Before
    fun setUp() {
        assumeTrue("no internet", YtDlpTestSupport.internetAvailable())
        YtDlpTestSupport.maybeUpdate(ctx)
        work = File(ctx.filesDir, "work/ytdlp-test-${UUID.randomUUID()}").apply { mkdirs() }
        Log.i(TAG, "url=$url")
    }

    @After
    fun tearDown() {
        if (::work.isInitialized) work.deleteRecursively()
    }

    @Test
    fun infoHasTitleAndDuration() = runBlocking<Unit> {
        val info = YtDlpDownloader(ctx).info(url, "test-info-${UUID.randomUUID()}")
        val v = info.videoInfo
        Log.i(TAG, "info: title='${v.title}' duration=${v.duration} live=${info.isLive} knownBytes=${info.knownBytes}")
        assertTrue("empty title", v.title.isNotBlank())
        if (YtDlpTestSupport.overrideUrl == null) assertTrue("duration ${v.duration}", v.duration in 18..20)
        assertFalse(info.isLive)
    }

    @Test
    fun audioOnlyDownloadHasAudio() = runBlocking<Unit> {
        val dl = YtDlpDownloader(ctx)
        val info = dl.info(url, "test-info-${UUID.randomUUID()}")
        var last = -1
        val media = dl.download(info, work, audioOnly = true, processId = "test-dl-${UUID.randomUUID()}") { last = it }
        Log.i(TAG, "audio only: ${media.audio.name} ${media.audio.length()} bytes ${trackMimes(media.audio)} progress=$last")
        assertNull(media.video)
        assertTrue(media.audio.canonicalPath.startsWith(File(work, "dl").canonicalPath))
        assertTrue(trackMimes(media.audio).any { it.startsWith("audio/") })
        assertEquals(100, last)
    }

    @Test
    fun videoDownloadHasAudioAndVideo() = runBlocking<Unit> {
        val dl = YtDlpDownloader(ctx)
        val info = dl.info(url, "test-info-${UUID.randomUUID()}")
        var last = -1
        val media = dl.download(info, work, audioOnly = false, processId = "test-dl-${UUID.randomUUID()}") { last = it }
        val video = media.video
        Log.i(TAG, "video: audio=${media.audio.name} ${trackMimes(media.audio)} video=${video?.name} ${video?.let(::trackMimes)} progress=$last")
        assertTrue("no video file", video != null)
        assertTrue(trackMimes(video!!).any { it.startsWith("video/") })
        assertTrue(trackMimes(media.audio).any { it.startsWith("audio/") })
        assertEquals(100, last)
    }

    /**
     * Diagnostic for the JS-challenge check: a `--simulate` run without `--no-warnings`, with its stderr in
     * logcat (tag MuremYtDlpTest). Fails only if yt-dlp cannot run at all.
     */
    @Test
    fun stderrWarningsAreLogged() = runBlocking<Unit> {
        YtDlpRuntime.ensureInit(ctx)
        val id = "test-sim-${UUID.randomUUID()}"
        val req = YoutubeDLRequest(emptyList())
            .addCommands(listOf("--simulate", "--no-playlist", "--ignore-config", "-f", YtDlpFormats.VIDEO, "--", url))
        try {
            val r = runYtDlp(id) { YoutubeDL.getInstance().execute(req, id, null) }
            Log.i(TAG, "simulate exit=${r.exitCode}")
            r.err.lines().filter { it.isNotBlank() }.forEach { Log.i(TAG, "stderr: $it") }
        } catch (e: YoutubeDLException) {
            Log.w(TAG, "simulate failed (${YtDlpErrors.classify(e.message.orEmpty())}): ${YtDlpErrors.tail(e.message.orEmpty())}")
        }
        Log.i(TAG, "yt-dlp version: ${YtDlpRuntime.version(ctx)}")
    }
}
