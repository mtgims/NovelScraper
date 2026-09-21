package com.novelscraper.app.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** Never offered on desktop (no embedded browser, see hasWebView); if the route is
 *  ever reached, go straight back. */
@Composable
actual fun NuBrowserScreen(onBack: () -> Unit, onScraped: () -> Unit, startUrl: String?) {
    LaunchedEffect(Unit) { onBack() }
}
