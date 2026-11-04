package com.musicremover.app.processing.internal

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicremover.app.R
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * "Log in with browser" without a real login: dummy cookies are set with CookieManager on a local page
 * loaded as https://www.instagram.com/, then saved. No network is used.
 */
@RunWith(AndroidJUnit4::class)
class WebLoginCookiesTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val store = CookieStore(ctx)
    private lateinit var webView: WebView
    private val stored get() = File(ctx.filesDir, "${CookieStore.DIR}/${CookieStore.FILE}")

    @Before
    fun setUp() = runBlocking {
        store.remove()
        val started = CountDownLatch(1)
        instr.runOnMainSync {
            webView = WebView(ctx)
            WebLoginCookies.configure(webView) { started.countDown() }
        }
        WebLoginCookies.clearAll(webView)
        instr.runOnMainSync {
            webView.loadDataWithBaseURL("https://www.instagram.com/", "<html><body>login</body></html>", "text/html", "utf-8", null)
        }
        assertTrue("page did not start", started.await(20, TimeUnit.SECONDS))
        setDummyCookies("DUMMY_SESSION_123456")
    }

    @After
    fun tearDown() = runBlocking {
        store.remove()
        WebLoginCookies.clearAll(webView)
        instr.runOnMainSync { webView.destroy() }
    }

    private fun setDummyCookies(session: String) {
        val cm = CookieManager.getInstance()
        cm.setCookie("https://www.instagram.com", "sessionid=$session; Secure")
        cm.setCookie("https://www.instagram.com", "csrftoken=DUMMY_CSRF_123456; Secure")
        cm.setCookie("https://i.instagram.com", "ds_user_id=DUMMY_UID_123456; Secure")
        cm.flush()
    }

    @Test
    fun saveStoresValidNetscapeAndClearsWebView() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val r = WebLoginCookies.saveAndClear(store, LoginSites.INSTAGRAM, "https://www.instagram.com/", webView)
        assertTrue("$r", r is CookieStore.SaveResult.Saved)
        r as CookieStore.SaveResult.Saved
        assertEquals(CookiesTxt.Summary(3, listOf("instagram.com")), r.captured)
        assertEquals(CookiesTxt.Summary(3, listOf("instagram.com")), store.summary())
        val text = stored.readText()
        assertEquals(CookiesTxt.Result.Valid(CookiesTxt.Summary(3, listOf("instagram.com"))), CookiesTxt.validate(text))
        val session = text.lines().single { it.contains("\tsessionid\t") }.split('\t')
        assertEquals(listOf(".instagram.com", "TRUE", "/", "TRUE"), session.take(4))
        assertEquals(listOf("sessionid", "DUMMY_SESSION_123456"), session.drop(5))
        val exp = session[4].toLong()
        assertTrue("expiry $exp", exp in (now + 364L * 86400)..(now + 366L * 86400))
        assertTrue(text.lines().any { it.endsWith("\tds_user_id\tDUMMY_UID_123456") })
        // Nothing left in the WebView cookie jar
        val cm = CookieManager.getInstance()
        for (url in LoginSites.INSTAGRAM.cookieUrls) assertTrue(url, cm.getCookie(url).isNullOrBlank())
        assertFalse(cm.hasCookies())
    }

    @Test
    fun mergeKeepsOtherSitesAndReplacesSameName() = runBlocking {
        val src = File(ctx.cacheDir, "dummy-cookies.txt").apply {
            writeText("# Netscape HTTP Cookie File\n.murem-test.invalid\tTRUE\t/\tFALSE\t2000000000\tmurem_test\tDUMMY_VALUE_123456\n")
        }
        assertTrue(store.import(Uri.fromFile(src)) is CookieStore.ImportResult.Imported)
        src.delete()
        assertTrue(WebLoginCookies.saveAndClear(store, LoginSites.INSTAGRAM, null, webView) is CookieStore.SaveResult.Saved)
        setDummyCookies("DUMMY_SESSION_CHANGED_789")
        val r = WebLoginCookies.saveAndClear(store, LoginSites.INSTAGRAM, null, webView)
        assertTrue("$r", r is CookieStore.SaveResult.Saved)
        assertEquals(CookiesTxt.Summary(4, listOf("instagram.com", "murem-test.invalid")), store.summary())
        val lines = stored.readText().lines()
        assertEquals(1, lines.count { it.contains("\tsessionid\t") })
        assertTrue(lines.any { it.endsWith("\tsessionid\tDUMMY_SESSION_CHANGED_789") })
        assertTrue(lines.any { it.endsWith("\tmurem_test\tDUMMY_VALUE_123456") })
    }

    @Test
    fun emptyJarSavesNothingAndKeepsTheFile() = runBlocking {
        WebLoginCookies.clearAll(webView)
        assertEquals(CookieStore.SaveResult.NoCookies, WebLoginCookies.saveAndClear(store, LoginSites.INSTAGRAM, null, webView))
        assertFalse(stored.exists())
    }

    @Test
    fun webViewIsLockedDown() {
        // WebView may only be touched on the main thread; assertions run on the test thread
        val got = mutableMapOf<String, Any?>()
        val urls = listOf(
            "http://example.com/", "intent://x#Intent;end", "file:///sdcard/x", "javascript:alert(1)",
            "https://www.instagram.com/accounts/login/",
        )
        instr.runOnMainSync {
            val s = webView.settings
            got["js"] = s.javaScriptEnabled
            got["file"] = s.allowFileAccess
            got["content"] = s.allowContentAccess
            got["mixed"] = s.mixedContentMode
            got["thirdParty"] = CookieManager.getInstance().acceptThirdPartyCookies(webView)
            val client = webView.webViewClient
            got["client"] = client is WebLoginCookies.Client
            for (u in urls) got[u] = client.shouldOverrideUrlLoading(webView, request(u))
        }
        assertEquals(true, got["js"])
        assertEquals(false, got["file"])
        assertEquals(false, got["content"])
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, got["mixed"])
        assertEquals(false, got["thirdParty"])
        assertEquals(true, got["client"])
        for (u in urls.dropLast(1)) assertEquals("$u not blocked", true, got[u])
        assertEquals("https blocked", false, got[urls.last()])
    }

    @Test
    fun backupRulesExcludeSecrets() {
        val required = setOf("file" to "cookies/", "file" to "work/", "root" to "app_webview/")
        val full = excludes(R.xml.backup_rules)
        assertTrue("$full", full.getValue("full-backup-content").containsAll(required))
        val extraction = excludes(R.xml.data_extraction_rules)
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertNotNull(section, extraction[section])
            assertTrue("$section: ${extraction[section]}", extraction.getValue(section).containsAll(required))
        }
    }

    /** `<exclude>` domain/path pairs grouped by their parent element. */
    private fun excludes(id: Int): Map<String, Set<Pair<String, String>>> {
        val out = mutableMapOf<String, MutableSet<Pair<String, String>>>()
        val stack = ArrayDeque<String>()
        ctx.resources.getXml(id).use { p ->
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (p.name == "exclude") {
                            out.getOrPut(stack.last()) { mutableSetOf() } +=
                                p.getAttributeValue(null, "domain") to p.getAttributeValue(null, "path")
                        }
                        stack.addLast(p.name)
                    }
                    XmlPullParser.END_TAG -> stack.removeLast()
                }
            }
        }
        return out
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
