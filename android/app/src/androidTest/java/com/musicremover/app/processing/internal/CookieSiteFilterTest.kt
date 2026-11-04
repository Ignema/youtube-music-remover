package com.musicremover.app.processing.internal

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicremover.app.MainActivity
import com.musicremover.app.processing.BackendProvider
import com.musicremover.app.processing.InternalBackend
import com.musicremover.app.processing.JobEvent
import com.musicremover.app.processing.ProcessOptions
import com.musicremover.app.processing.internal.YtDlpTestSupport.TAG
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-site switches reach the job file: a disabled site's cookies never leave the store, and every cookie
 * copy (job file, preview dir, worker work dir) is gone afterwards. Dummy `DUMMY_` values only; the yt-dlp
 * cases need internet.
 */
@RunWith(AndroidJUnit4::class)
class CookieSiteFilterTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val store = CookieStore(ctx)
    private lateinit var work: File
    private val instagram = listOf(
        ".instagram.com\tTRUE\t/\tTRUE\t2000000000\tsessionid\tDUMMY_INSTA_SESSION_1",
        "#HttpOnly_.instagram.com\tTRUE\t/\tTRUE\t2000000000\tcsrftoken\tDUMMY_INSTA_CSRF_1",
    )
    private val example = ".example.org\tTRUE\t/\tFALSE\t2000000000\texample_name\tDUMMY_EXAMPLE_VALUE_1"

    @Before
    fun setUp() {
        store.deleteAll()
        work = File(ctx.filesDir, "work/cookie-filter-test-${UUID.randomUUID()}").apply { mkdirs() }
        val src = File(ctx.cacheDir, "dummy-sites.txt")
        src.writeText((listOf("# Netscape HTTP Cookie File") + instagram + example).joinToString("\n", postfix = "\n"))
        val r = store.import(Uri.fromFile(src))
        src.delete()
        assertEquals(
            CookieStore.ImportResult.Imported(CookiesTxt.Summary(3, listOf("example.org", "instagram.com"))),
            r,
        )
        assertTrue(store.setEnabled("example.org", false))
    }

    @After
    fun tearDown() {
        store.deleteAll()
        if (::work.isInitialized) work.deleteRecursively()
    }

    @Test
    fun disabledSiteIsNotInTheJobFile() {
        val dir = File(work, "cookies")
        val c = store.copyForJob(dir)
        assertNotNull(c)
        val text = c!!.file.readText()
        c.file.delete()
        assertFalse("example.org line in the job file", text.lines().any { it.contains("example.org") })
        assertFalse("disabled value in the job file", "DUMMY_EXAMPLE" in text)
        assertFalse("DUMMY_EXAMPLE_VALUE_1" in c.secrets)
        for (l in instagram) assertTrue("Instagram line missing", l in text.lines())
        val sites = store.sites().associateBy { it.site }
        assertFalse(sites.getValue("example.org").enabled)
        assertTrue(sites.getValue("instagram.com").enabled)
    }

    @Test
    fun ytDlpRunWithTheEnabledSitesDeletesTheJobFile() = runBlocking<Unit> {
        assumeTrue("no internet", YtDlpTestSupport.internetAvailable())
        YtDlpTestSupport.maybeUpdate(ctx)
        val dir = File(work, "cookies")
        val info = withJobCookies(store, dir) { cookies ->
            assertNotNull(cookies)
            assertFalse("DUMMY_EXAMPLE" in cookies!!.file.readText())
            YtDlpDownloader(ctx).info(YtDlpTestSupport.url, "test-site-filter-${UUID.randomUUID()}", cookies)
        }
        Log.i(TAG, "site filter: info '${info.videoInfo.title}'")
        assertFalse("job cookie file not deleted", File(dir, CookieStore.FILE).exists())
    }

    @Test
    fun throwingBlockDeletesTheJobFile() {
        val dir = File(work, "cookies")
        try {
            withJobCookies(store, dir) { cookies ->
                assertTrue(cookies!!.file.exists())
                throw IllegalStateException("boom")
            }
        } catch (_: IllegalStateException) {
        }
        assertFalse(File(dir, CookieStore.FILE).exists())
    }

    @Test
    fun previewLeavesNoPreviewDir() = runBlocking<Unit> {
        assumeTrue("no internet", YtDlpTestSupport.internetAvailable())
        YtDlpTestSupport.maybeUpdate(ctx)
        val info = BackendProvider(ctx).internal.info(YtDlpTestSupport.url)
        Log.i(TAG, "site filter preview: '${info.title}'")
        val left = ctx.cacheDir.list()?.filter { it.startsWith("preview-") }.orEmpty()
        assertEquals("preview dirs left: $left", emptyList<String>(), left)
    }

    @Test
    fun internalUrlJobRemovesItsWorkDir() = runBlocking<Unit> {
        assumeTrue("device below on-device thresholds", InternalCapability.current(ctx) == null)
        assumeTrue("no internet", YtDlpTestSupport.internetAvailable())
        YtDlpTestSupport.maybeUpdate(ctx)
        val url = UrlNormalizer.normalize(YtDlpTestSupport.url)!!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // The worker may only start its foreground service while the app is visible
        ActivityScenario.launch(MainActivity::class.java).use {
            // Let the ViewModel's stale-work cleanup finish before the job creates its work dir
            delay(3_000)
            val backend = BackendProvider(ctx).internal
            val jobId = backend.startUrl(url, ProcessOptions(ModelCatalog.HQ3, audioOnly = true, bitrate = "128k"))
            Log.i(TAG, "site filter job $jobId")
            val end = withTimeout(20 * 60_000L) {
                backend.events(jobId).first {
                    it is JobEvent.Done || it is JobEvent.Failed || it is JobEvent.ConnectionLost
                }
            }
            assertTrue("job ended with $end", end is JobEvent.Done)
            val dir = File(InternalBackend.workRoot(ctx), jobId)
            val listing = shell("run-as ${ctx.packageName} ls -a files/work")
            Log.i(TAG, "site filter: ls files/work after $jobId: ${listing.lines().filter { it.isNotBlank() }}")
            assertFalse("work dir left: $dir", dir.exists())
            assertFalse("work dir listed", listing.lines().any { it.trim() == jobId })
        }
    }

    private fun shell(cmd: String): String {
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }
}
