package com.novelscraper.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelscraper.app.net.Account
import com.novelscraper.app.platform.settingsStore
import com.novelscraper.app.sync.SyncSettings
import java.time.LocalDate

/** The day the sync server is switched off, and everything on it deleted. */
private val SERVER_SYNC_ENDS: LocalDate = LocalDate.of(2026, 10, 21)
private const val KEY_DISMISSED = "server_sync_notice_dismissed"

/**
 * Told once per launch to anyone still syncing through a server account: the
 * server is going, on a set day, and the new sync replaces it. Not shown to
 * anyone without an account, nor to anyone who has already set the new sync up,
 * nor again once they say so.
 */
@Composable
fun ServerSyncNotice(onSetUp: () -> Unit) {
    val account by Account.state.collectAsState()
    val sync by SyncSettings.state.collectAsState()
    val prefs = remember { settingsStore("ui") }
    var dismissed by remember { mutableStateOf(prefs.getBoolean(KEY_DISMISSED, false)) }
    var closed by rememberSaveable { mutableStateOf(false) }

    if (account !is Account.State.SignedIn || sync != null || dismissed || closed) return

    val ended = !LocalDate.now().isBefore(SERVER_SYNC_ENDS)
    val day = "21 October"
    AlertDialog(
        onDismissRequest = { closed = true },
        title = { Text(if (ended) "Server sync has ended" else "Server sync ends on $day") },
        text = {
            Column {
                Text(
                    if (ended) {
                        "NovelScraper's sync server was switched off on $day, and what it held was " +
                            "deleted. Your library is still here, on this device."
                    } else {
                        "NovelScraper's sync server is being switched off on $day, and what it holds " +
                            "will be deleted with it. Your library stays on this device either way."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "To keep your devices in step, use the new sync: Settings, Sync, Start syncing " +
                        "on one device, then Enter a sync code on the others. They sync whenever " +
                        "they are on the same Wi-Fi, and nothing goes through a server.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { closed = true; onSetUp() }) { Text("Set up sync") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    prefs.putBoolean(KEY_DISMISSED, true)
                    dismissed = true
                }) { Text("Don't show again") }
                TextButton(onClick = { closed = true }) { Text("Not now") }
            }
        },
    )
}
