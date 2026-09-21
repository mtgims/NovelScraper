package com.novelscraper.app.ui.screen

import androidx.compose.runtime.Composable

/** An in-app browser on NovelUpdates: log in, open a series, pick a translation
 *  group and scrape it. Needs an embedded web view (Android WebView). */
@Composable
expect fun NuBrowserScreen(onBack: () -> Unit, onScraped: () -> Unit, startUrl: String? = null)
