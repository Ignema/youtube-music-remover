package com.musicremover.app.processing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicremover.app.R
import com.musicremover.app.processing.internal.YtDlpTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The text the preview shows for a link that yt-dlp refuses with "login required". Runs only with
 * `muremLoginUrl` (a login-gated post, or any Instagram reel with the bundled yt-dlp 2025.11.12, whose
 * Instagram extractor reports every reel that way); on the emulator also pass `muremProxy`.
 */
@RunWith(AndroidJUnit4::class)
class InternalLinkErrorTextTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun loginRequiredSaysUpdateOrServerMode() = runBlocking<Unit> {
        val url = InstrumentationRegistry.getArguments().getString("muremLoginUrl")
        assumeTrue("no muremLoginUrl", !url.isNullOrBlank())
        YtDlpTestSupport.applyProxy()
        val expected = ctx.getString(R.string.internal_url_login_required)
        try {
            BackendProvider(ctx).internal.info(url!!)
            fail("info succeeded for $url")
        } catch (e: IllegalStateException) {
            Log.i(YtDlpTestSupport.TAG, "login error text: ${e.message}")
            assertTrue("unexpected text: ${e.message}", e.message.orEmpty().startsWith(expected))
        }
    }
}
