package com.musicremover.app.processing.internal

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The "Log in with browser" WebView: locked-down setup, reading its cookies into [CookieStore], and
 * wiping every WebView cookie, storage and cache entry afterwards so no session copy stays in
 * `app_webview`. Call from the main thread. Cookie names and values are never logged.
 */
object WebLoginCookies {
    /** https-only WebView without file/content access, mixed content or third-party cookies. */
    // Login pages need JavaScript; the WebView only loads https pages and exposes no JavaScript interface
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, onPage: (String) -> Unit = {}) {
        webView.settings.apply {
            // The login pages need JavaScript and DOM storage; no JavaScript interface is added
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        // The default onReceivedSslError cancels the load
        webView.webViewClient = Client(onPage)
    }

    /** Blocks every navigation that [LoginSites.isAllowedNavigation] rejects. */
    class Client(private val onPage: (String) -> Unit) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !LoginSites.isAllowedNavigation(request.url?.toString())

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            url?.let(onPage)
        }
    }

    /** Removes all WebView cookies, web storage, cache, history and form data. Does not destroy [webView]. */
    suspend fun clearAll(webView: WebView?) = withContext(Dispatchers.Main + NonCancellable) {
        val cm = CookieManager.getInstance()
        suspendCancellableCoroutine { cont -> cm.removeAllCookies { cont.resume(Unit) } }
        cm.flush()
        WebStorage.getInstance().deleteAllData()
        webView?.apply {
            clearCache(true)
            clearHistory()
            clearFormData()
        }
    }

    @Volatile private var leftoverChecked = false

    /**
     * Once per process: if a WebView data dir exists (e.g. the process died mid-login, so no wipe ran),
     * clear it. Skipped when the WebView was never used, so normal starts don't pay for WebView init.
     */
    suspend fun wipeLeftoverSession(context: android.content.Context) {
        if (leftoverChecked) return
        leftoverChecked = true
        val dataDir = java.io.File(context.applicationInfo.dataDir, "app_webview")
        if (dataDir.exists()) clearAll(null)
    }

    /**
     * Saves the WebView's cookies for [site] (plus the [currentUrl] host if it is on the site's domain)
     * into [store], then clears the WebView in any case.
     */
    suspend fun saveAndClear(store: CookieStore, site: LoginSites.Site, currentUrl: String?, webView: WebView?): CookieStore.SaveResult {
        try {
            val entries = withContext(Dispatchers.Main) {
                val cm = CookieManager.getInstance()
                val now = System.currentTimeMillis() / 1000
                LoginSites.cookieUrls(site, currentUrl)
                    .flatMap { CookiesTxt.fromCookieHeader(cm.getCookie(it), site.cookieDomain, now) }
                    // The first URL wins for a name (www before i. for Instagram)
                    .distinctBy { it.name }
            }
            // Anonymous cookies (e.g. Instagram's csrftoken/mid without sessionid) are not a login
            if (entries.isNotEmpty() && !LoginSites.isLoggedIn(site, entries.map { it.name })) {
                return CookieStore.SaveResult.NotLoggedIn
            }
            return withContext(Dispatchers.IO) { store.saveCaptured(site, entries) }
        } finally {
            clearAll(webView)
        }
    }
}
