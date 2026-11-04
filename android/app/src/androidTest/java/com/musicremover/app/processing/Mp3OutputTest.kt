package com.musicremover.app.processing

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.musicremover.app.MainActivity
import com.musicremover.app.processing.internal.InternalCapability
import com.musicremover.app.processing.internal.ModelCatalog
import com.musicremover.app.processing.internal.ModelManager
import com.musicremover.app.processing.internal.Mp3Support
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MP3 output through the bundled ffmpeg: the probe finds libmp3lame, and an Audio-only Internal job at
 * 192k on the 10 s clip (filesDir/test/clip10.mp4, provisioned by run-connected.ps1) with HQ_3 writes a
 * `-vocals-only.mp3`. The result is copied to /sdcard/Download/murem-mp3-test.mp3 (as the shell user) so
 * the PC can pull it and check it with ffprobe.
 */
@RunWith(AndroidJUnit4::class)
class Mp3OutputTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val clip = File(ctx.filesDir, "test/clip10.mp4")

    @Before
    fun setUp() {
        assumeTrue("device below on-device thresholds: ${InternalCapability.current(ctx)}", InternalCapability.current(ctx) == null)
        assertTrue("test clip missing: run run-connected.ps1 (it pushes clip10.mp4)", clip.isFile)
        assertTrue("${ModelCatalog.HQ3} not provisioned", ModelManager.get(ctx).isDownloaded(ModelCatalog.HQ3))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        Mp3Support.resetForTest()
    }

    // executeShellCommandRw (the copy for the PC) is API 31+; the test AVD is API 35
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
    @Test
    fun audioOnlyMp3Job() = runBlocking {
        assertTrue("libmp3lame not found by the probe", Mp3Support.available(ctx))
        val inputS = audioDurationS(clip)
        Log.i(TAG, "input ${clip.name} audio duration $inputS s")
        ActivityScenario.launch(MainActivity::class.java).use {
            // Let the ViewModel's stale-work cleanup finish before the job creates its work dir
            delay(3_000)
            val backend = BackendProvider(ctx).internal
            val options = ProcessOptions(ModelCatalog.HQ3, audioOnly = true, bitrate = "192k", outputFormat = "mp3")
            val jobId = backend.startFile(Uri.fromFile(clip), clip.name, options)
            Log.i(TAG, "job $jobId")
            val end = withTimeout(20 * 60_000L) {
                backend.events(jobId).first {
                    if (it is JobEvent.Progress) Log.i(TAG, "progress ${it.status} ${it.progress}")
                    it is JobEvent.Done || it is JobEvent.Failed || it is JobEvent.ConnectionLost
                }
            }
            assertTrue("job ended with $end", end is JobEvent.Done)
            val done = end as JobEvent.Done
            assertTrue("result ${done.filename} is not -vocals-only.mp3", done.filename.endsWith("-vocals-only.mp3"))
            val file = done.file
            assertNotNull("result not stored", file)
            val outS = audioDurationS(file!!)
            Log.i(TAG, "result ${done.filename} ${file.length()} bytes, $outS s (input $inputS s)")
            assertEquals("audio/mpeg", audioMime(file))
            copyToDownloads(file)
        }
    }

    /** Writes [file] to [DOWNLOAD_PATH] through `dd` run as the shell user (the app cannot share its files dir). */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
    private fun copyToDownloads(file: File) {
        val ui = instr.uiAutomation
        ui.executeShellCommand("rm -f $DOWNLOAD_PATH").close()
        val fds = ui.executeShellCommandRw("dd of=$DOWNLOAD_PATH")
        val stdout = fds[0]
        ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { sink ->
            FileInputStream(file).use { it.copyTo(sink) }
        }
        // dd exits after stdin is closed; reading stdout to EOF waits for it
        ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
        val check = ui.executeShellCommand("stat -c %s $DOWNLOAD_PATH")
        val size = ParcelFileDescriptor.AutoCloseInputStream(check).use { it.readBytes().decodeToString().trim() }
        Log.i(TAG, "copied to $DOWNLOAD_PATH: $size bytes")
        assertEquals("copy size", file.length().toString(), size)
    }

    private fun audioDurationS(file: File): Double = withAudioTrack(file) { it.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 }

    private fun audioMime(file: File): String = withAudioTrack(file) { it.getString(MediaFormat.KEY_MIME).orEmpty() }

    private fun <T> withAudioTrack(file: File, block: (MediaFormat) -> T): T {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) return block(f)
            }
            throw AssertionError("no audio track in ${file.name}")
        } finally {
            ex.release()
        }
    }

    private companion object {
        const val TAG = "MuremMp3Test"
        const val DOWNLOAD_PATH = "/sdcard/Download/murem-mp3-test.mp3"
    }
}
