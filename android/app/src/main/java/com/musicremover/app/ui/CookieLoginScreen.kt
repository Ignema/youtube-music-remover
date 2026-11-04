package com.musicremover.app.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.musicremover.app.MainViewModel
import com.musicremover.app.R
import com.musicremover.app.processing.internal.LoginSites
import com.musicremover.app.processing.internal.WebLoginCookies
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * "Log in with browser": the user logs in to [site] in a locked-down WebView, then Save stores the site's
 * cookies (see [WebLoginCookies]). Save, Cancel, back and leaving the screen all clear the WebView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieLoginScreen(vm: MainViewModel, site: LoginSites.Site, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val cancel: () -> Unit = {
        if (!busy) {
            busy = true
            scope.launch {
                WebLoginCookies.clearAll(webView)
                onBack()
            }
        }
    }
    BackHandler(onBack = cancel)

    // Start from an empty cookie jar, so only this login is saved
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        WebLoginCookies.clearAll(view)
        view.loadUrl(site.startUrl)
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                // clearAll(null) below cannot reach the HTTP cache once the view is destroyed
                clearCache(true)
                clearHistory()
                clearFormData()
                removeAllViews()
                destroy()
            }
            webView = null
            // In case the screen was left another way than Save or Cancel
            MainScope().launch { WebLoginCookies.clearAll(null) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cookies_login_title)) },
                navigationIcon = {
                    IconButton(onClick = cancel) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.cookies_login_cancel))
                    }
                },
                actions = {
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                vm.saveWebCookies(site, currentUrl, webView)
                                onBack()
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(stringResource(R.string.cookies_login_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (LoginSites.isEmbeddedBlockedLogin(currentUrl)) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.cookies_login_oauth_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).also { view ->
                        WebLoginCookies.configure(view) { url -> currentUrl = url }
                        webView = view
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
