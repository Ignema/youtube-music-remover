package com.musicremover.app.processing

import android.Manifest
import android.app.ActivityManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicremover.app.MainActivity
import com.musicremover.app.data.SettingsStore
import com.musicremover.app.processing.internal.AudioDecoder
import com.musicremover.app.processing.internal.InternalCapability
import com.musicremover.app.processing.internal.ModelCatalog
import com.musicremover.app.processing.internal.ModelManager
import com.musicremover.app.processing.internal.ModelNoticeRequiredException
import com.musicremover.app.processing.internal.PcmFileReader
import com.musicremover.app.processing.internal.PerformanceSettings
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The Phase 4 catalog models on-device: the files provisioned by run-connected.ps1 (filesDir/models) match
 * models.json, and an Internal Audio-only job on the 10 s H.264/AAC clip (filesDir/test/clip10.mp4, also
 * provisioned) succeeds with each model. Also re-downloads 3_9662, and runs HQ_3 with batch 2 when the
 * phone has the memory for it. Skipped (not passed) below the on-device RAM thresholds.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class InternalModelsTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val models = ModelManager.get(ctx)
    private val clip = File(ctx.filesDir, "test/clip10.mp4")

    @Before
    fun setUp() {
        assumeTrue("device below on-device thresholds: ${InternalCapability.current(ctx)}", InternalCapability.current(ctx) == null)
        assertTrue("test clip missing: run run-connected.ps1 (it pushes clip10.mp4)", clip.isFile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        SettingsStore(ctx).perfBatch = 1
        val mi = memInfo()
        Log.i(TAG, "totalMem ${mi.totalMem} availMem ${mi.availMem}")
    }

    @Test fun a_vocFt() = modelCase(VOC_FT)

    @Test fun b_mdx3() = modelCase(MDX3)

    @Test fun c_kara2() = modelCase(KARA2)

    @Test fun d_kim2() {
        // The notice gate holds even for a provisioned file, and before any network access
        ctx.getSharedPreferences(SettingsStore.PREFS, android.content.Context.MODE_PRIVATE).edit()
            .remove(SettingsStore.NOTICE_PREFIX + KIM).commit()
        assertTrue(models.needsNotice(KIM))
        try {
            runBlocking { models.ensure(KIM) }
            fail("ensure must refuse Kim without the notice")
        } catch (_: ModelNoticeRequiredException) {
        }
        models.acceptNotice(KIM)
        assertFalse(models.needsNotice(KIM))
        modelCase(KIM)
    }

    @Test fun e_batch2() {
        val mi = memInfo()
        val msg = "totalMem ${mi.totalMem} availMem ${mi.availMem}"
        assumeTrue("batch 2 not allowed on this device: $msg", mi.totalMem >= PerformanceSettings.BATCH2_MIN_TOTAL_MEM)
        assumeTrue("not enough free memory for batch 2 now: $msg", mi.availMem >= PerformanceSettings.BATCH2_MIN_AVAIL_MEM)
        verifyProvisioned(ModelCatalog.HQ3)
        val settings = SettingsStore(ctx)
        try {
            settings.perfBatch = 1
            val one = runJob(ModelCatalog.HQ3)
            settings.perfBatch = 2
            val two = runJob(ModelCatalog.HQ3)
            val log = ownLog()
            val jobLines = log.lines().filter { two.jobId in it }
            assertTrue("no run config line for ${two.jobId}", jobLines.any { "run config" in it && "batch=2" in it && "batchReduced=false" in it })
            assertFalse("batch 2 refused for ${two.jobId}", jobLines.any { "batch 2 refused" in it })
            assertTrue("batch 1 ${one.durationS} s vs batch 2 ${two.durationS} s", abs(one.durationS - two.durationS) <= 0.1)
            Log.i(TAG, "batch case: batch1 ${one.ms} ms, batch2 ${two.ms} ms ($msg)")
        } finally {
            settings.perfBatch = 1
        }
    }

    @Test fun z_downloadMdx3() {
        assumeTrue("no internet", reachable("https://github.com"))
        assertTrue("delete refused", models.delete(MDX3))
        assertFalse(File(ctx.filesDir, "models/$MDX3").exists())
        val started = SystemClock.elapsedRealtime()
        val file = runBlocking { withTimeout(15 * 60_000L) { models.ensure(MDX3) } }
        Log.i(TAG, "download $MDX3 ${file.length()} bytes in ${SystemClock.elapsedRealtime() - started} ms")
        assertEquals(spec(MDX3).sha256, sha256(file))
        assertTrue(models.isDownloaded(MDX3))
    }

    private fun modelCase(name: String) {
        verifyProvisioned(name)
        val r = runJob(name)
        Log.i(TAG, "model case $name: ${r.ms} ms, ${r.durationS} s, rms ${r.rms}")
    }

    /** The provisioned file exists, has the catalog size and the catalog SHA-256. */
    private fun verifyProvisioned(name: String) {
        val f = File(ctx.filesDir, "models/$name")
        assertTrue("$name not provisioned in filesDir/models (run run-connected.ps1)", f.isFile)
        assertTrue("$name size ${f.length()} vs ${spec(name).size}", models.isDownloaded(name))
        assertEquals("$name SHA-256", spec(name).sha256, sha256(f))
    }

    private class JobResult(val jobId: String, val durationS: Double, val rms: Double, val ms: Long)

    /** Audio-only job on the clip; asserts success, an .m4a within 0.1 s of the input, non-silent. */
    private fun runJob(name: String): JobResult = runBlocking {
        val inputS = audioDurationS(clip)
        ActivityScenario.launch(MainActivity::class.java).use {
            // Let the ViewModel's stale-work cleanup finish before the job creates its work dir
            delay(3_000)
            val backend = BackendProvider(ctx).internal
            val started = SystemClock.elapsedRealtime()
            val jobId = backend.startFile(Uri.fromFile(clip), clip.name, ProcessOptions(name, audioOnly = true, bitrate = "128k"))
            Log.i(TAG, "job $jobId model $name")
            val end = withTimeout(20 * 60_000L) {
                backend.events(jobId).first {
                    if (it is JobEvent.Progress) Log.i(TAG, "progress ${it.status} ${it.progress} eta ${it.etaSec}")
                    it is JobEvent.Done || it is JobEvent.Failed || it is JobEvent.ConnectionLost
                }
            }
            val ms = SystemClock.elapsedRealtime() - started
            assertTrue("$name job ended with $end", end is JobEvent.Done)
            val done = end as JobEvent.Done
            assertTrue("result ${done.filename} is not .m4a", done.filename.endsWith(".m4a"))
            val file = done.file
            assertNotNull("result not stored", file)
            val outS = audioDurationS(file!!)
            assertTrue("$name result $outS s vs input $inputS s", abs(outS - inputS) <= 0.1)
            val rms = rms(file)
            assertTrue("$name result is silent (rms $rms)", rms > 1e-3)
            JobResult(jobId, outS, rms, ms)
        }
    }

    private fun spec(name: String) = ModelCatalog.spec(ctx, name) ?: throw AssertionError("$name not in catalog")

    private fun memInfo(): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }

    private fun audioDurationS(file: File): Double {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) return f.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
            }
            throw AssertionError("no audio track in ${file.name}")
        } finally {
            ex.release()
        }
    }

    /** RMS of the decoded result (both channels). */
    private suspend fun rms(file: File): Double {
        val pcm = File(ctx.cacheDir, "models-test-${file.name}.f32")
        try {
            AudioDecoder(ctx).decode(Uri.fromFile(file), pcm, audioOnly = true, bitrateBps = 128_000) {}
            PcmFileReader(pcm).use { r ->
                val n = r.frames.toInt()
                val l = FloatArray(n)
                val rr = FloatArray(n)
                r.read(0, l, rr, 0, n)
                var sum = 0.0
                for (i in 0 until n) sum += l[i].toDouble() * l[i] + rr[i].toDouble() * rr[i]
                return if (n == 0) 0.0 else sqrt(sum / (2.0 * n))
            }
        } finally {
            pcm.delete()
        }
    }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** This app's own log lines (the worker runs in the test process). */
    private fun ownLog(): String {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "MuremInternal:I"))
        return p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor() }
    }

    private fun reachable(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try { c.responseCode in 200..399 } finally { c.disconnect() }
    } catch (e: Exception) {
        Log.w(TAG, "HEAD $url failed", e)
        false
    }

    private companion object {
        const val TAG = "MuremModelsTest"
        const val VOC_FT = "UVR-MDX-NET-Voc_FT.onnx"
        const val MDX3 = "UVR_MDXNET_3_9662.onnx"
        const val KARA2 = "UVR_MDXNET_KARA_2.onnx"
        const val KIM = "Kim_Vocal_2.onnx"
    }
}
