package com.novelscraper.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.novelscraper.app.extensions.SiteChecks
import com.novelscraper.app.platform.showToast
import kotlinx.coroutines.launch

/**
 * When a source asks for a browser check, offers to open it in a real browser.
 * Shown wherever the reader is, since the check can come from any screen (a
 * chapter, a search, a download).
 */
@Composable
fun SiteCheckPrompt() {
    val pending by SiteChecks.pending.collectAsState()
    val status by SiteChecks.status.collectAsState()
    val url = pending ?: return
    val scope = rememberCoroutineScope()
    val site = runCatching { java.net.URI(url).host }.getOrNull() ?: url

    AlertDialog(
        onDismissRequest = { if (status == null) SiteChecks.dismiss() },
        title = { Text("$site asks for a browser check") },
        text = {
            Text(
                status ?: if (SiteChecks.possible) {
                    "The site wants to see a browser before it hands over chapters, and no " +
                        "amount of cookies changes that. The app can open one, and from then on " +
                        "it loads this site's pages through it. The first time on this computer, " +
                        "the browser is downloaded (about 500 MB)."
                } else {
                    "The site wants to see a browser before it hands over chapters, which this " +
                        "device can't do. Read this novel on the phone, or import it as an EPUB."
                },
            )
        },
        confirmButton = {
            if (SiteChecks.possible) {
                TextButton(
                    enabled = status == null,
                    onClick = {
                        scope.launch {
                            val ok = SiteChecks.run()
                            showToast(
                                if (ok) "The site let us through: try again." else "The check didn't pass.",
                                long = true,
                            )
                        }
                    },
                ) { Text(if (status == null) "Open the check" else "Working…") }
            }
        },
        dismissButton = {
            TextButton(enabled = status == null, onClick = { SiteChecks.dismiss() }) { Text("Not now") }
        },
    )
}
