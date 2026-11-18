package com.novelscraper.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.novelscraper.app.platform.DesktopDirs
import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.SessionMarker
import com.novelscraper.app.platform.appVersion
import com.novelscraper.app.platform.showToast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The desktop's [SessionMarker], and what it found at this start. */
object UncleanExit {
    val marker by lazy {
        SessionMarker(File(DesktopDirs.cache, "session"), File(DesktopDirs.cache, "novelscraper.log"))
    }

    private val _report = MutableStateFlow<SessionMarker.Report?>(null)
    val report: StateFlow<SessionMarker.Report?> = _report.asStateFlow()

    /** Once at start, holding the instance lock, before the log is written to. */
    fun begin() {
        _report.value = marker.begin(appVersion)?.also {
            Log.w("Main", "the last session (${it.version}, started ${it.started}) ended without closing" +
                (it.doing?.let { d -> ", while $d" } ?: ""))
        }
    }

    fun dismiss() { _report.value = null }
}

/** Says so when the last session ended without closing, with what it was
 *  doing, and offers the details to pass on. */
@Composable
fun UncleanExitPrompt() {
    val report by UncleanExit.report.collectAsState()
    val r = report ?: return
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = { UncleanExit.dismiss() },
        title = { Text("NovelScraper closed unexpectedly") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The last session ended without closing properly" +
                        (r.doing?.let { ", while $it" } ?: "") + ".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "If it happens again, the details (what it was doing and the end of its log) help find out why.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { UncleanExit.dismiss() }) { Text("OK") } },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(r.details()))
                showToast("Details copied")
                UncleanExit.dismiss()
            }) { Text("Copy details") }
        },
    )
}
