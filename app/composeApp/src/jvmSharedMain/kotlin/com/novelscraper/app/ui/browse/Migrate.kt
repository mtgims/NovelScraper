package com.novelscraper.app.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.extensions.NovelItem
import com.novelscraper.app.library.Library
import com.novelscraper.app.library.LibBook
import com.novelscraper.app.library.MigrationPreview
import com.novelscraper.app.platform.showToast
import kotlinx.coroutines.launch

/**
 * Move a novel to another source: the search of every source, starting from
 * its title, without the source it is on now. Picking a result fetches that
 * novel's chapters and says what would move before anything does.
 */
@Composable
fun MigrateScreen(
    bookId: Int,
    onBack: () -> Unit,
    onDone: (newBookId: Int) -> Unit,
    onOpenSource: (pluginId: String, query: String) -> Unit,
) {
    val book by produceState<LibBook?>(null, bookId) { value = Library.store.book(bookId) }
    val b = book ?: return
    var picked by remember { mutableStateOf<Pair<String, NovelItem>?>(null) }

    GlobalSearchScreen(
        initialQuery = b.title,
        onBack = onBack,
        onPick = { _, pluginId, item -> picked = pluginId to item },
        onOpenSource = onOpenSource,
        title = "Migrate",
        exclude = b.pluginId,
        vmKey = "migrate-$bookId",
        header = {
            Text(
                "Pick “${b.title}” on another source. What has been read, where reading left off, the rating " +
                    "and collections move with it.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        },
    )

    picked?.let { (pluginId, item) ->
        MigrateDialog(b, pluginId, item, onDismiss = { picked = null }, onDone = onDone)
    }
}

@Composable
private fun MigrateDialog(
    from: LibBook,
    pluginId: String,
    item: NovelItem,
    onDismiss: () -> Unit,
    onDone: (Int) -> Unit,
) {
    val site = remember(pluginId) { Extensions.installed.value.firstOrNull { it.id == pluginId }?.name ?: pluginId }
    var target by remember { mutableStateOf<Pair<Int, MigrationPreview>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var keepOld by remember { mutableStateOf(false) }
    var downloadAgain by remember { mutableStateOf(true) }
    var moving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The new source's whole chapter list first: matching needs it, and a
    // source that fails here leaves the library untouched.
    LaunchedEffect(pluginId, item.path) {
        try {
            val id = Library.store.openSource(pluginId, item.path, item.name, item.cover)
            if (id == from.id) error("That is the novel being moved.")
            Library.store.refresh(id)
            target = id to Library.store.migrationPreview(from.id, id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = describe(e)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!moving) onDismiss() },
        title = { Text("Move to $site?") },
        text = {
            val t = target
            when {
                failed != null -> Text("Couldn't get its chapters from $site: $failed",
                    color = MaterialTheme.colorScheme.error)
                t == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Getting “${item.name}” from $site…", modifier = Modifier.padding(start = 12.dp))
                }
                else -> Preview(from, site, t.second, keepOld, { keepOld = it }, downloadAgain, { downloadAgain = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = target != null && !moving,
                onClick = {
                    val (toId, _) = target ?: return@TextButton
                    moving = true
                    scope.launch {
                        try {
                            Library.store.migrate(from.id, toId, keepOld, downloadAgain)
                            if (downloadAgain) com.novelscraper.app.library.ChapterDownloads.start()
                            showToast("“${from.title}” now reads from $site.")
                            onDone(toId)
                        } catch (e: Exception) {
                            moving = false
                            failed = describe(e)
                        }
                    }
                },
            ) { Text(if (moving) "Moving…" else "Move") }
        },
        dismissButton = { TextButton(enabled = !moving, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What moves, and where reading carries on, for the reader to check before it happens. */
@Composable
private fun Preview(
    from: LibBook,
    site: String,
    p: MigrationPreview,
    keepOld: Boolean,
    onKeepOld: (Boolean) -> Unit,
    downloadAgain: Boolean,
    onDownloadAgain: (Boolean) -> Unit,
) {
    val here = p.siteHere.ifBlank { "this source" }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Fact("Chapters", "${p.chaptersHere} on $here, ${p.chaptersThere} on $site")
        Fact("Read", "${p.readHere} here become ${p.readThere} there")
        if (p.resumeHere != null) {
            Fact("Reading at", "${p.resumeHere}\nbecomes ${p.resumeThere ?: "the start"}")
        }
        when {
            p.pastTheEnd -> Warning("$site doesn't go as far as you have read: reading carries on at its last chapter.")
            !p.byNumber && p.readHere > 0 ->
                Warning("These chapters don't number the same way, so they were matched by their order. " +
                    "Check the place after moving.")
            p.resumeHere != null && !p.exact ->
                Warning("$site has no chapter like the one you were reading; the nearest was taken.")
        }
        Choice("Keep the $here version in the library too", keepOld, onKeepOld)
        if (!keepOld) {
            Text(
                "The $here version leaves the library" +
                    (if (p.downloadedHere > 0) ", with its ${p.downloadedHere} downloaded chapters" else "") +
                    ". Your other devices do the same when they sync.",
                style = MaterialTheme.typography.bodySmall,
                color = if (p.downloadedHere > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (p.downloadedHere > 0) Choice("Download the same chapters from $site", downloadAgain, onDownloadAgain)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Warning(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

@Composable
private fun Choice(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
