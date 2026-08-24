package com.novelscraper.app.ui.screen

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelscraper.app.net.NuExtract
import com.novelscraper.app.net.NuGroup
import com.novelscraper.app.ui.NewScrapeViewModel
import com.novelscraper.app.ui.ScrapeUi

private const val NU_HOME = "https://www.novelupdates.com/"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NuBrowserScreen(onBack: () -> Unit, onScraped: () -> Unit) {
    val ctx = LocalContext.current
    val scrapeVm: NewScrapeViewModel = viewModel()
    val scrapeUi by scrapeVm.ui.collectAsState()

    var currentUrl by remember { mutableStateOf(NU_HOME) }
    var address by remember { mutableStateOf(NU_HOME) }
    var groups by remember { mutableStateOf<List<NuGroup>?>(null) }  // non-null -> chooser open
    var busy by remember { mutableStateOf<String?>(null) }           // overlay message when set
    var resolving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { CookieManager.getInstance().setAcceptCookie(true) }

    val webView = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = false
                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    address = url
                    // The chosen group's /extnu/ link redirects to the translator's
                    // site; once we've left novelupdates.com we have the TL URL.
                    if (resolving && url != "about:blank" && !NuExtract.isNovelUpdatesHost(url)) {
                        resolving = false
                        busy = "Starting scrape…"
                        scrapeVm.scrape(url, null, null, null)
                    }
                }
            }
            loadUrl(NU_HOME)
        }
    }
    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    BackHandler { if (webView.canGoBack()) webView.goBack() else onBack() }

    LaunchedEffect(scrapeUi) {
        when (val s = scrapeUi) {
            is ScrapeUi.Done -> { scrapeVm.reset(); busy = null; onScraped() }
            is ScrapeUi.Error -> { busy = null; resolving = false; error = s.message; scrapeVm.reset() }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add from NovelUpdates", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, singleLine = true,
                    label = { Text("NovelUpdates URL") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { if (address.isNotBlank()) webView.loadUrl(address.trim()) }) {
                    Text("Go")
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                busy?.let { msg ->
                    Column(
                        Modifier.fillMaxSize().background(Color(0xAA000000)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Text(msg, color = Color.White, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }

            Button(
                onClick = {
                    error = null
                    busy = "Reading translations…"
                    webView.evaluateJavascript(NuExtract.EXTRACT_JS) { result ->
                        val gs = NuExtract.parse(result)
                        busy = null
                        if (gs.isEmpty()) {
                            error = "No translation groups found. Make sure you're logged into " +
                                "NovelUpdates and on a series page."
                        } else {
                            groups = gs
                        }
                    }
                },
                enabled = NuExtract.isSeriesUrl(currentUrl) && busy == null && !resolving,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                Text("Use this novel")
            }
        }
    }

    groups?.let { gs ->
        AlertDialog(
            onDismissRequest = { groups = null },
            title = { Text("Choose a translation") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Pick which group's version to scrape (chapter counts are approximate):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    gs.forEach { g ->
                        TextButton(
                            onClick = {
                                groups = null
                                resolving = true
                                busy = "Opening ${g.name}…"
                                webView.loadUrl(g.firstExtnu)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${g.name}  ·  ${g.count} ch", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { groups = null }) { Text("Cancel") } },
        )
    }
}
