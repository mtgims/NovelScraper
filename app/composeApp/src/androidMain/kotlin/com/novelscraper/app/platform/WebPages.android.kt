package com.novelscraper.app.platform

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.novelscraper.app.extensions.SiteChecks
import com.novelscraper.app.net.PrivateHosts
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

actual val hasInAppBrowser: Boolean = true

private const val CLEARANCE = "cf_clearance"

/**
 * A site's page in a WebView the reader can see and use: for looking at the site,
 * and for a check that wants a person (a box to tick, a puzzle), which the
 * offscreen one can never pass.
 *
 * It is the same WebView, user agent and cookie store the app's own browser
 * fetches use, so a check passed here is passed for them, and what the site set
 * goes to the sources' cookie jar when the page closes. Only for the site that
 * was opened (and the address it sent the page on to): a link followed to some
 * other site hands that one nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun InAppBrowser(url: String, check: Boolean, onClose: () -> Unit) {
    val start = remember(url) { url.toHttpUrlOrNull()?.takeIf { !PrivateHosts.isPrivateHost(it.host) } }
    if (start == null) {
        LaunchedEffect(url) { showToast("That address can't be opened here."); onClose() }
        return
    }
    var view by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(url) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    // The opened address, and wherever its first page landed (www., a redirect).
    val kept = remember { linkedSetOf(url) }
    var landed by remember { mutableStateOf(false) }
    // A clearance left from an earlier visit is not this check passing: only a
    // new one, or a changed one, is.
    val clearanceBefore = remember { cookie(url, CLEARANCE) }

    DisposableEffect(Unit) {
        onDispose {
            kept.forEach { keepCookies(it) }
            CookieManager.getInstance().flush()
            if (check) SiteChecks.endInApp()
            view?.destroy()
        }
    }

    if (check) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(700)
                val now = kept.firstNotNullOfOrNull { cookie(it, CLEARANCE) }
                if (now != null && now != clearanceBefore) {
                    showToast("The site let the app in.")
                    SiteChecks.passedInApp()
                    onClose()
                    break
                }
            }
        }
    }

    BackHandler { if (canGoBack) view?.goBack() else onClose() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") } },
                title = {
                    Column {
                        Text(title.ifBlank { start.host }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        Text(current.toHttpUrlOrNull()?.host ?: start.host, maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (check) TextButton(onClick = onClose) { Text("Done") }
                    IconButton(onClick = { view?.reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
                    if (!check) IconButton(onClick = { openInBrowser(current) }) {
                        Icon(Icons.Filled.OpenInBrowser, "Open in the browser")
                    }
                },
            )
            if (check) {
                Text(
                    "Pass the site's check below. This closes by itself once the site lets the app in; " +
                        "if it doesn't, tap Done when the site shows its page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (progress in 1..99) LinearProgressIndicator(
                progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = browserUserAgent
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        val guard = PublicOnly(true)
                        webViewClient = object : WebViewClient() {
                            // Web pages only: an intent:// or market:// link has
                            // nothing to show here.
                            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                                request.url.scheme?.lowercase() !in setOf("http", "https")

                            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest) =
                                guard.check(request)

                            override fun onPageStarted(v: WebView, u: String, favicon: Bitmap?) {
                                current = u
                            }

                            override fun onPageFinished(v: WebView, u: String) {
                                current = u
                                canGoBack = v.canGoBack()
                                if (!landed) {
                                    landed = true
                                    kept += u
                                }
                            }

                            override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) {
                                canGoBack = v.canGoBack()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(v: WebView, p: Int) { progress = p }
                            override fun onReceivedTitle(v: WebView, t: String?) { title = t.orEmpty() }
                        }
                        loadUrl(url)
                        view = this
                    }
                },
            )
        }
    }
}

/** The value of cookie [name] the WebView holds for [url], if any. */
private fun cookie(url: String, name: String): String? =
    CookieManager.getInstance().getCookie(url).orEmpty().split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
