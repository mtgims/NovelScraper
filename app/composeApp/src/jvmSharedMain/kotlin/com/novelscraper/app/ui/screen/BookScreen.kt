package com.novelscraper.app.ui.screen

import com.novelscraper.app.platform.PlatformBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.CollectionRead
import com.novelscraper.app.data.ReadingProgressRead
import com.novelscraper.app.net.Downloads
import com.novelscraper.app.platform.showToast
import com.novelscraper.app.net.Net
import com.novelscraper.app.ui.BookState
import com.novelscraper.app.ui.BookViewModel
import com.novelscraper.app.ui.components.ChapterRow
import com.novelscraper.app.ui.components.StarRating
import com.novelscraper.app.ui.components.VolumeHeaderRow
import com.novelscraper.app.ui.components.groupVolumes
import com.novelscraper.app.ui.theme.Kicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    bookId: Int,
    onBack: () -> Unit,
    onOpenReader: (position: Int) -> Unit,
) {
    PlatformBackHandler(onBack = onBack)
    val vm: BookViewModel = viewModel()
    LaunchedEffect(bookId) { vm.ensureLoaded(bookId); vm.refreshProgress(bookId) }
    val state by vm.state.collectAsState()
    val collections by vm.collections.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }

    // Surface delete/update results as a toast.
    val action by vm.action.collectAsState()
    LaunchedEffect(action) {
        action?.let { showToast(it, long = true); vm.clearAction() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text((state as? BookState.Data)?.book?.title ?: "Book",
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if ((state as? BookState.Data)?.book?.volumes?.isNotEmpty() == true) {
                            DropdownMenuItem(
                                text = { Text("Download") },
                                onClick = { menuOpen = false; showDownload = true },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Check for new chapters") },
                            onClick = { menuOpen = false; vm.checkForNewChapters(bookId) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete novel") },
                            onClick = { menuOpen = false; showDelete = true },
                        )
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is BookState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is BookState.Error -> Text(s.message, Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error)
                is BookState.Data -> BookContent(s, collections, vm, bookId, onOpenReader)
            }
        }
    }

    val book = (state as? BookState.Data)?.book
    if (showDownload && book != null) {
        DownloadDialog(
            book = book,
            onVolume = { v ->
                Downloads.volume(book.id, book.slug, v, book.title)
                showToast("Downloading volume $v…")
            },
            onAll = {
                Downloads.all(book.id, book.slug, book.title)
                showToast("Downloading all volumes…")
            },
            onDismiss = { showDownload = false },
        )
    }

    if (showDelete) {
        val title = (state as? BookState.Data)?.book?.title ?: "this novel"
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete novel?") },
            text = { Text("Remove \"$title\" and all its chapters from your library? " +
                "Your reading position is archived, so re-adding it later restores where you were.") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; vm.deleteBook(bookId, onBack) }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BookContent(
    data: BookState.Data,
    collections: List<CollectionRead>,
    vm: BookViewModel,
    bookId: Int,
    onOpenReader: (Int) -> Unit,
) {
    val readSet = data.progress?.read_positions?.toSet() ?: emptySet()
    val resumePos = data.progress?.last_position?.takeIf { it > 0 } ?: 1
    val hasProgress = (data.progress?.read_count ?: 0) > 0
    val volumes = remember(data.chapters) { groupVolumes(data.chapters) }

    // Collapsible volumes — collapse all but the one you're currently reading so a
    // 30-volume book isn't 3000 rows to scroll.
    var expanded by remember(data.chapters) {
        mutableStateOf(
            setOf(data.chapters.firstOrNull { it.position == resumePos }?.volume
                ?: volumes.firstOrNull()?.number ?: 0),
        )
    }
    var selection by remember(data.chapters) { mutableStateOf<Set<Int>>(emptySet()) }
    val selecting = selection.isNotEmpty()
    var showReset by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = selection.size,
                onMarkRead = { vm.setPositionsRead(bookId, selection.toList(), true); selection = emptySet() },
                onMarkUnread = { vm.setPositionsRead(bookId, selection.toList(), false); selection = emptySet() },
                onClear = { selection = emptySet() },
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                BookHeader(
                    data.book, data.progress, hasProgress, resumePos, collections,
                    onToggleCollection = vm::toggleCollection,
                    onRate = vm::setRating,
                    onOpenReader = onOpenReader,
                    onMarkAll = { vm.markAllRead(bookId) },
                    onReset = { showReset = true },
                )
            }
            volumes.forEach { vol ->
                val isOpen = vol.number in expanded
                item(key = "vol-${vol.number}") {
                    VolumeHeaderRow(
                        number = vol.number, chapterCount = vol.chapters.size, expanded = isOpen,
                        onClick = {
                            expanded = if (isOpen) expanded - vol.number else expanded + vol.number
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
                if (isOpen) {
                    items(vol.chapters, key = { "ch-${it.position}" }) { ch ->
                        val isSel = ch.position in selection
                        ChapterRow(
                            ch = ch,
                            read = ch.position in readSet,
                            current = ch.position == resumePos && hasProgress,
                            selectionMode = selecting,
                            selected = isSel,
                            onClick = {
                                if (selecting) {
                                    selection = if (isSel) selection - ch.position else selection + ch.position
                                } else onOpenReader(ch.position)
                            },
                            onLongClick = { selection = selection + ch.position },
                            onToggleRead = {
                                vm.setChapterRead(bookId, ch.position, ch.position !in readSet)
                            },
                        )
                    }
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Reset reading progress?") },
            text = { Text("This clears every read mark and your resume point for this book.") },
            confirmButton = {
                TextButton(onClick = { vm.resetProgress(bookId); showReset = false }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } },
        )
    }
}

/** Pick a volume to save as EPUB, or all of them as a zip. The dialog stays open
 *  so several volumes can be queued in one go; each lands in Downloads with a
 *  system notification. */
@Composable
private fun DownloadDialog(
    book: BookRead,
    onVolume: (Int) -> Unit,
    onAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download") },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                if (book.volumes.size > 1) {
                    item {
                        DownloadRow(
                            label = "All volumes",
                            detail = "${book.volumes.size} EPUBs in one zip",
                            onClick = onAll,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    }
                }
                items(book.volumes.sortedBy { it.number }, key = { it.number }) { v ->
                    DownloadRow(
                        label = "Volume ${v.number}",
                        detail = if (v.chapter_count == 1) "1 chapter" else "${v.chapter_count} chapters",
                        onClick = { onVolume(v.number) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun DownloadRow(label: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.labelMedium.copy(fontFamily = Kicker.fontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.Download, contentDescription = "Download $label",
            tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
        }
        Text("$count selected", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
        TextButton(onClick = onMarkUnread) { Text("Unread") }
        TextButton(onClick = onMarkRead) { Text("Read") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookHeader(
    book: BookRead,
    progress: ReadingProgressRead?,
    hasProgress: Boolean,
    resumePos: Int,
    collections: List<CollectionRead>,
    onToggleCollection: (Int) -> Unit,
    onRate: (Int) -> Unit,
    onOpenReader: (Int) -> Unit,
    onMarkAll: () -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row {
            Box(
                Modifier.width(120.dp).height(168.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (book.has_cover) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalPlatformContext.current)
                            .data(Net.coverUrl(book.id)).crossfade(true).build(),
                        contentDescription = book.title, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(book.title.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(book.author, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                StarRating(book.rating, size = 34.dp, onRate = onRate,
                    modifier = Modifier.padding(top = 6.dp).offset(x = (-4).dp))
                if (progress != null && progress.total_chapters > 0) {
                    Text("${progress.read_count} / ${progress.total_chapters} · ${progress.percent_read.toInt()}%",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = Kicker.fontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp))
                    LinearProgressIndicator(
                        progress = { (progress.percent_read / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }

        Button(
            onClick = { onOpenReader(resumePos) },
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null,
                modifier = Modifier.size(20.dp).padding(end = 4.dp))
            Text(if (hasProgress) "Continue · chapter $resumePos" else "Start reading")
        }

        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AssistChip(
                onClick = onMarkAll,
                label = { Text("Mark all read") },
                leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = null,
                    modifier = Modifier.size(18.dp)) },
            )
            AssistChip(
                onClick = onReset,
                label = { Text("Reset") },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null,
                    modifier = Modifier.size(18.dp)) },
            )
        }

        if (collections.isNotEmpty()) {
            Text("COLLECTIONS", style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                collections.forEach { c ->
                    val inIt = c.id in book.collection_ids
                    FilterChip(
                        selected = inIt,
                        onClick = { onToggleCollection(c.id) },
                        label = { Text(c.name) },
                        leadingIcon = if (inIt) {
                            { Icon(Icons.Filled.Check, contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 20.dp))
    }
}
