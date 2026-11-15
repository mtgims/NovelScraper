package com.novelscraper.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelscraper.app.library.BackupNovel
import com.novelscraper.app.library.Library
import com.novelscraper.app.platform.PlatformBackHandler
import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.ui.components.ContentWidth

/**
 * Novels from a reading-progress backup that aren't in the library yet
 * ([com.novelscraper.app.library.StatsRestore]): each can be found in the
 * sources, which adds it with its progress, or forgotten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreListScreen(onBack: () -> Unit, onFind: (BackupNovel) -> Unit) {
    PlatformBackHandler(onBack = onBack)
    val waiting by Library.restore.waiting.collectAsState()
    var confirmAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("From a backup") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (waiting.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Forget all") } },
        )
        if (waiting.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text(
                    "Nothing is waiting: every novel from the backup has its progress back, or was forgotten.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        } else ContentWidth {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (isDesktop) 24.dp else 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "intro") {
                    Text(
                        "These novels were in the backup but aren't in the library. Find one, pick the right " +
                            "result, and it's added with its chapters marked read. A novel added any other way " +
                            "under the same title gets its progress too.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                    )
                }
                items(waiting, key = { it.title }) { n ->
                    WaitingRow(n, onFind = { onFind(n) }, onForget = { Library.restore.forget(n) })
                }
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Forget every novel on the list?") },
            text = { Text("Their progress from the backup won't come back on its own. Importing the backup again brings the list back.") },
            confirmButton = {
                TextButton(onClick = { confirmAll = false; Library.restore.forgetAll(); onBack() }) { Text("Forget all") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WaitingRow(n: BackupNovel, onFind: () -> Unit, onForget: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    detail(n),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TextButton(onClick = onFind) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                Text("Find", Modifier.padding(start = 6.dp))
            }
            IconButton(onClick = onForget) {
                Icon(Icons.Filled.Close, "Forget ${n.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun detail(n: BackupNovel): String = buildString {
    append(if (n.read == 1) "1 chapter read" else "${n.read} chapters read")
    if (n.total > 0) append(" of ${n.total}")
    if (n.site.isNotBlank()) append(" · was on ${n.site}")
}
