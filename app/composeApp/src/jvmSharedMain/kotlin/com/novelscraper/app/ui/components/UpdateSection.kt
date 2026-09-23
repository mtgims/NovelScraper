package com.novelscraper.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.platform.openInBrowser
import com.novelscraper.app.update.AppUpdates

/**
 * The version running, and what to do about a newer one.
 *
 * Updating used to mean going to the releases page and fetching a file by hand,
 * which is a thing nobody remembers to do. The app asks the same page itself.
 */
@Composable
fun UpdateSection() {
    val state by AppUpdates.state.collectAsState()

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Version ${AppUpdates.current}", style = MaterialTheme.typography.bodyMedium)
                val note = when (val s = state) {
                    is AppUpdates.State.Idle ->
                        if (s.upToDate) "The newest there is." else "Updates come from the releases page."
                    AppUpdates.State.Checking -> "Looking…"
                    is AppUpdates.State.Available -> "Version ${s.version} is out."
                    is AppUpdates.State.Downloading -> "Getting version ${s.version}…"
                    is AppUpdates.State.Ready ->
                        if (isDesktop) "Version ${s.version} is ready to put in place."
                        else "Version ${s.version} is downloaded."
                    is AppUpdates.State.Failed -> s.message
                }
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is AppUpdates.State.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            when (val s = state) {
                AppUpdates.State.Checking -> CircularProgressIndicator(Modifier.size(20.dp))
                is AppUpdates.State.Available ->
                    Button(onClick = { AppUpdates.download() }) { Text("Update") }
                is AppUpdates.State.Ready ->
                    Button(onClick = { AppUpdates.install() }) {
                        Text(if (isDesktop) "Install and restart" else "Install")
                    }
                is AppUpdates.State.Downloading -> Unit
                else -> OutlinedButton(onClick = { AppUpdates.check() }) { Text("Check") }
            }
        }

        (state as? AppUpdates.State.Downloading)?.let { s ->
            val fraction = if (s.total > 0) s.done.toFloat() / s.total else 0f
            Column(Modifier.padding(top = 8.dp)) {
                LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
                Text(
                    "${s.done / 1_000_000} of ${s.total / 1_000_000} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        (state as? AppUpdates.State.Available)?.let { s ->
            if (s.notes.isNotBlank()) {
                Text(
                    s.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { openInBrowser(AppUpdates.RELEASES_PAGE) }) { Text("What's new") }
                TextButton(onClick = { AppUpdates.dismiss() }) { Text("Not now") }
            }
        }
    }
}
