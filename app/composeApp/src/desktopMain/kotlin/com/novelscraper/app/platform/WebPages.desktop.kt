package com.novelscraper.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** The computer opens sites in its own browser, and runs checks in the window
 *  it drives for that ([passSiteCheck]). */
actual val hasInAppBrowser: Boolean = false

@Composable
actual fun InAppBrowser(url: String, check: Boolean, onClose: () -> Unit) {
    // Never routed to here (hasInAppBrowser), but a page asked for still opens.
    LaunchedEffect(url) { openInBrowser(url); onClose() }
}
