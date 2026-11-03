package com.musicremover.app.processing

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicremover.app.MainActivity
import com.musicremover.app.data.HistoryStore
import com.musicremover.app.processing.internal.InternalCapability
import com.musicremover.app.processing.internal.ModelCatalog
import com.musicremover.app.processing.internal.UrlNormalizer
import com.musicremover.app.processing.internal.YtDlpDownloader
import com.musicremover.app.processing.internal.YtDlpTestSupport
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Link -> MP4 through the real worker: yt-dlp download, HQ3 separation, mux. Skipped (not passed) on a
 * device below the on-device RAM thresholds or without internet. The model is pushed before the run or
 * downloaded by the worker (the 20 min timeout covers it).
 */
@RunWith(AndroidJUnit4::class)
class InternalUrlPipelineTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    @Test
    fun linkBecomesMp4WithOneVideoAndOneAacTrack() = runBlocking<Unit> {
        assumeTrue("device below on-device thresholds: ${InternalCapability.current(ctx)}", InternalCapability.current(ctx) == null)
        assumeTrue("no internet", YtDlpTestSupport.internetAvailable())
        YtDlpTestSupport.maybeUpdate(ctx)
        val url = UrlNormalizer.normalize(YtDlpTestSupport.url)!!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val expectedS = YtDlpDownloader(ctx).info(url, "test-pipe-info-${UUID.randomUUID()}").videoInfo.duration

        // The worker may only start its foreground service while the app is visible
        ActivityScenario.launch(MainActivity::class.java).use {
            // Let the ViewModel's stale-work cleanup finish before the job creates its work dir
            delay(3_000)
            val backend = BackendProvider(ctx).internal
            val jobId = backend.startUrl(url, ProcessOptions(ModelCatalog.HQ3, audioOnly = false, bitrate = "128k"))
            Log.i(YtDlpTestSupport.TAG, "pipeline job $jobId for $url")
            val end = withTimeout(20 * 60_000L) {
                backend.events(jobId).first {
                    if (it is JobEvent.Progress) Log.i(YtDlpTestSupport.TAG, "progress ${it.status} ${it.progress}")
                    it is JobEvent.Done || it is JobEvent.Failed || it is JobEvent.ConnectionLost
                }
            }
            assertTrue("job ended with $end", end is JobEvent.Done)
            val file = (end as JobEvent.Done).file
            assertNotNull("result not stored", file)
            Log.i(YtDlpTestSupport.TAG, "result ${end.filename} ${file!!.length()} bytes")

            val ex = MediaExtractor()
            val formats = try {
                ex.setDataSource(file.path)
                (0 until ex.trackCount).map { ex.getTrackFormat(it) }
            } finally {
                ex.release()
            }
            val mimes = formats.map { it.getString(MediaFormat.KEY_MIME).orEmpty() }
            Log.i(YtDlpTestSupport.TAG, "result tracks $mimes")
            assertEquals("video tracks in $mimes", 1, mimes.count { it.startsWith("video/") })
            assertEquals("audio tracks in $mimes", 1, mimes.count { it.startsWith("audio/") })
            val audio = formats.first { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            assertEquals("audio/mp4a-latm", audio.getString(MediaFormat.KEY_MIME))
            if (expectedS > 0) {
                val audioS = audio.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
                assertTrue("audio $audioS s vs info $expectedS s", abs(audioS - expectedS) <= 1.0)
            } else {
                Log.i(YtDlpTestSupport.TAG, "info has no duration; duration check skipped")
            }

            val item = HistoryStore(ctx).getAll().firstOrNull { it.jobId == jobId }
            assertNotNull("no history entry for $jobId", item)
            assertFalse(item!!.isFileUpload)
            assertEquals(url, item.url)
            assertTrue("no title", !item.ytTitle.isNullOrBlank())
        }
    }
}
