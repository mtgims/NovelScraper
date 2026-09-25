package com.novelscraper.app.ui.screen

import com.novelscraper.app.platform.PlatformBackHandler
import androidx.compose.foundation.background
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import com.novelscraper.app.ui.components.ColumnScrollbar
import com.novelscraper.app.ui.components.ListScrollbar
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelscraper.app.library.LibBook
import com.novelscraper.app.library.LibChapter
import com.novelscraper.app.library.LibCollection
import com.novelscraper.app.library.LibProgress
import com.novelscraper.app.platform.openInBrowser
import com.novelscraper.app.tts.AudiobookExport
import com.novelscraper.app.ui.AudioRange
import com.novelscraper.app.ui.DownloadChoice
import com.novelscraper.app.ui.components.BookCover
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.OpenInBrowser
import com.novelscraper.app.platform.showToast
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
    val vm: BookViewModel = viewModel(key = "book-$bookId") { BookViewModel(bookId) }
    val book by vm.book.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val progress by vm.progress.collectAsState()
    val collections by vm.collections.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val error by vm.error.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    // True once the page's own heading has scrolled out of sight, at which point
    // the bar takes the title over.
    val titleInBar = remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }

    // Surface action results as a toast.
    val action by vm.action.collectAsState()
    LaunchedEffect(action) {
        action?.let { showToast(it, long = true); vm.clearAction() }
    }

    Scaffold(
        // The same ground as the rest of the app: the lighter shade belongs to
        // the chapters sitting on it, not to the page behind them.
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                // The heading in the page says the title; repeating it in the bar
                // said everything twice. It appears once the heading scrolls off.
                title = {
                    if (titleInBar.value) {
                        Text(book?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val b = book ?: return@TopAppBar
                    if (refreshing) CircularProgressIndicator(Modifier.size(22.dp))
                    b.webUrl?.let { url ->
                        IconButton(onClick = { openInBrowser(url) }) {
                            Icon(Icons.Filled.OpenInBrowser, contentDescription = "Open website")
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Check for new chapters") },
                            onClick = { menuOpen = false; vm.checkForNewChapters() },
                        )
                        if (b.downloadedCountOf(chapters) > 0) DropdownMenuItem(
                            text = { Text("Remove downloads") },
                            onClick = { menuOpen = false; vm.removeDownloads() },
                        )
                        DropdownMenuItem(
                            text = { Text("Save as audio") },
                            onClick = { menuOpen = false; showAudio = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Save as EPUB") },
                            onClick = { menuOpen = false; vm.saveEpub(b.title, b.author) },
                        )
                        if (b.inLibrary) DropdownMenuItem(
                            text = { Text("Remove from library") },
                            onClick = { menuOpen = false; showDelete = true },
                        )
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            val b = book
            val list = chapters
            when {
                b == null || list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() && refreshing -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() && error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::refresh) { Text("Try again") }
                }
                else -> BookContent(b, list, progress, collections, vm, onOpenReader,
                    onDownload = { showDownload = true }, onRemove = { showDelete = true },
                    titleInBar = titleInBar)
            }
        }
    }

    val b = book
    if (showAudio && b != null) {
        AudioExportDialog(
            onChoice = { showAudio = false; vm.exportAudio(it) },
            onDismiss = { showAudio = false },
        )
    }

    if (showDownload && b != null) {
        DownloadDialog(
            onChoice = { showDownload = false; vm.download(it) },
            onDismiss = { showDownload = false },
        )
    }

    if (showDelete && b != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(if (b.pluginId == null) "Delete novel?" else "Remove from library?") },
            text = {
                Text(
                    if (b.pluginId == null) "Remove \"${b.title}\" and all its chapters? It was imported from a " +
                        "file, so there is no source to add it back from: importing the EPUB again is the only way."
                    else "Remove \"${b.title}\" from your library, with its downloaded chapters? " +
                        "You can add it again from Browse."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    if (b.pluginId == null) vm.delete(onBack) else vm.removeFromLibrary()
                }) { Text(if (b.pluginId == null) "Delete" else "Remove") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun LibBook.downloadedCountOf(chapters: List<LibChapter>?) = chapters?.count { it.downloaded } ?: downloadedCount

// Above this width the book screen shows details and chapters side by side.
private val TWO_PANE_MIN_WIDTH = 900.dp
// The details column takes a share of the window rather than a fixed 420dp,
// which left a wide desktop window with a cramped column beside acres of list.
// Clamped so it neither squeezes the summary nor swallows the chapters.
/** Roughly how far the heading has to scroll before the bar takes the title. */
private const val TITLE_SCROLLED_AWAY_PX = 160

private val DETAILS_PANE_MIN = 380.dp
private val DETAILS_PANE_MAX = 560.dp
private const val DETAILS_PANE_SHARE = 0.32f

@Composable
private fun BookContent(
    book: LibBook,
    chapters: List<LibChapter>,
    progress: LibProgress?,
    collections: List<LibCollection>,
    vm: BookViewModel,
    onOpenReader: (Int) -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    titleInBar: MutableState<Boolean>,
) {
    val readSet = progress?.readPositions ?: emptySet()
    val resumePos = progress?.lastPosition?.takeIf { p -> chapters.any { it.position == p } } ?: 1
    val hasProgress = progress?.lastPosition != null || readSet.isNotEmpty()
    val volumes = remember(chapters) { groupVolumes(chapters) }
    // One volume (source novels): no volume headers, just the chapters.
    val flat = volumes.size <= 1
    val queued by vm.queued.collectAsState()
    val failedDownloads by vm.failedDownloads.collectAsState()
    val downloads by vm.downloads.collectAsState()

    // Collapsible volumes — collapse all but the one you're currently reading so a
    // 30-volume book isn't 3000 rows to scroll.
    var expanded by remember(chapters.size) {
        mutableStateOf(
            setOf(
                volumes.firstOrNull { vol -> vol.chapters.any { it.position == resumePos } }?.number
                    ?: volumes.firstOrNull()?.number ?: 0,
            ),
        )
    }
    var selection by remember(chapters.size) { mutableStateOf<Set<Int>>(emptySet()) }
    val selecting = selection.isNotEmpty()
    var showReset by remember { mutableStateOf(false) }

    val header: @Composable () -> Unit = {
        BookHeader(
            book, chapters.size, progress, hasProgress, resumePos, collections,
            queued = queued, failedDownloads = failedDownloads, downloadError = downloads.error,
            onRetryFailed = vm::retryFailedDownloads, onForgetFailed = vm::forgetFailedDownloads,
            onToggleCollection = vm::toggleCollection,
            onRate = vm::setRating,
            onOpenReader = onOpenReader,
            onAdd = vm::addToLibrary,
            onRemove = onRemove,
            onDownload = onDownload,
            onCancelDownloads = vm::cancelDownloads,
            onResumeDownloads = vm::resumeDownloads,
            onMarkAll = vm::markAllRead,
            onReset = { showReset = true },
        )
    }
    val chapters: LazyListScope.() -> Unit = {
        volumes.forEach { vol ->
            val isOpen = flat || vol.number in expanded
            if (!flat) item(key = "vol-${vol.number}") {
                VolumeHeaderRow(
                    number = vol.number, chapterCount = vol.chapters.size, label = vol.label,
                    expanded = isOpen,
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
                        read = ch.read,
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
                            vm.setChapterRead(ch.position, ch.position !in readSet)
                        },
                    )
                }
            }
        }
        item { Box(Modifier.height(24.dp)) }
    }
    val selectionBar: @Composable () -> Unit = {
        if (selecting) {
            SelectionBar(
                count = selection.size,
                onMarkRead = { vm.setPositionsRead(selection.toList(), true); selection = emptySet() },
                onMarkUnread = { vm.setPositionsRead(selection.toList(), false); selection = emptySet() },
                onClear = { selection = emptySet() },
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= TWO_PANE_MIN_WIDTH) {
            // Wide window: details on the left, the chapter list beside them.
            // Read here: inside the Row, RowScope hides BoxWithConstraints' maxWidth.
            val detailsWidth = (maxWidth * DETAILS_PANE_SHARE)
                .coerceIn(DETAILS_PANE_MIN, DETAILS_PANE_MAX)
            Row(Modifier.fillMaxSize()) {
                val detailScroll = rememberScrollState()
                LaunchedEffect(detailScroll) {
                    snapshotFlow { detailScroll.value > TITLE_SCROLLED_AWAY_PX }
                        .collect { titleInBar.value = it }
                }
                Box(Modifier.width(detailsWidth).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize().verticalScroll(detailScroll)) { header() }
                    ColumnScrollbar(detailScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    selectionBar()
                    Box(Modifier.fillMaxSize()) {
                        val listState = rememberLazyListState()
                        LazyColumn(Modifier.fillMaxSize(), state = listState, content = chapters)
                        ListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                selectionBar()
                val listState = rememberLazyListState()
                LaunchedEffect(listState) {
                    snapshotFlow {
                        listState.firstVisibleItemIndex > 0 ||
                            listState.firstVisibleItemScrollOffset > TITLE_SCROLLED_AWAY_PX
                    }.collect { titleInBar.value = it }
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item { header() }
                    chapters()
                }
            }
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Reset reading progress?") },
            text = { Text("This clears every read mark and your resume point for this book.") },
            confirmButton = {
                TextButton(onClick = { vm.resetProgress(); showReset = false }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } },
        )
    }
}

/** Progress while this novel is being read out to files. */
@Composable
private fun AudioExportRow(bookId: Int) {
    val export by AudiobookExport.state.collectAsState()
    if (!export.running || export.bookId != bookId) return
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Saving audio · ${export.done + 1} of ${export.total}",
                style = MaterialTheme.typography.bodySmall)
            if (export.chapter.isNotBlank()) {
                Text(export.chapter, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = { AudiobookExport.cancel() }) { Text("Cancel") }
    }
}

/** Which chapters to read out into audio files. */
@Composable
private fun AudioExportDialog(onChoice: (AudioRange) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as audio") },
        text = {
            Column {
                Text("Chapters are read by the on-device voice and saved to Downloads as WAV " +
                    "files, about 3 MB a minute. It takes a while: reading is faster than the " +
                    "voice can be made.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))
                DownloadRow("This chapter", "where you are now", onClick = { onChoice(AudioRange.ThisChapter) })
                DownloadRow("Next 10 chapters", "from where you are", onClick = { onChoice(AudioRange.Next10) })
                DownloadRow("All chapters", "the whole novel", onClick = { onChoice(AudioRange.All) })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Which chapters to download for reading offline. */
@Composable
private fun DownloadDialog(onChoice: (DownloadChoice) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download chapters") },
        text = {
            Column {
                Text("Downloaded chapters open without a connection.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))
                DownloadRow("Next 10 chapters", "from where you are", onClick = { onChoice(DownloadChoice.Next10) })
                DownloadRow("Unread chapters", "everything not read yet", onClick = { onChoice(DownloadChoice.Unread) })
                DownloadRow("All chapters", "the whole novel", onClick = { onChoice(DownloadChoice.All) })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
    book: LibBook,
    chapterCount: Int,
    progress: LibProgress?,
    hasProgress: Boolean,
    resumePos: Int,
    collections: List<LibCollection>,
    queued: Long,
    failedDownloads: Long,
    downloadError: String?,
    onRetryFailed: () -> Unit,
    onForgetFailed: () -> Unit,
    onToggleCollection: (Int) -> Unit,
    onRate: (Int) -> Unit,
    onOpenReader: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownloads: () -> Unit,
    onResumeDownloads: () -> Unit,
    onMarkAll: () -> Unit,
    onReset: () -> Unit,
) {
    var summaryOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row {
            BookCover(
                book, Modifier.width(120.dp).height(168.dp).clip(RoundedCornerShape(10.dp)),
                initialsStyle = MaterialTheme.typography.headlineSmall,
            )
            Column(Modifier.padding(start = 16.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                val line = listOfNotNull(
                    book.site.takeIf { it.isNotBlank() },
                    book.status.takeIf { it.isNotBlank() && !it.equals("Unknown", true) },
                ).joinToString(" · ")
                if (line.isNotEmpty()) Text(line.uppercase(),
                    style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                StarRating(book.rating, size = 34.dp, onRate = onRate,
                    modifier = Modifier.padding(top = 6.dp).offset(x = (-4).dp))
                if (progress != null && progress.total > 0) {
                    Text("${progress.readCount} / ${progress.total} · ${progress.percent.toInt()}%",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = Kicker.fontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp))
                    LinearProgressIndicator(
                        progress = { (progress.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }

        Button(
            onClick = { onOpenReader(resumePos) },
            enabled = chapterCount > 0,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null,
                modifier = Modifier.size(20.dp).padding(end = 4.dp))
            Text(if (hasProgress) "Continue · chapter $resumePos" else "Start reading")
        }

        FlowRow(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!book.inLibrary) {
                AssistChip(
                    onClick = onAdd,
                    label = { Text("Add to library") },
                    leadingIcon = { Icon(Icons.Filled.LibraryAdd, contentDescription = null,
                        modifier = Modifier.size(18.dp)) },
                )
            } else if (book.isSource) {
                FilterChip(
                    selected = true,
                    onClick = onRemove,
                    label = { Text("In library") },
                    leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                )
            }
            AssistChip(
                onClick = onDownload,
                enabled = chapterCount > 0,
                label = { Text("Download") },
                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null,
                    modifier = Modifier.size(18.dp)) },
            )
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

        if (failedDownloads > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$failedDownloads chapter${if (failedDownloads == 1L) "" else "s"} couldn't be downloaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onForgetFailed) { Text("Forget") }
                TextButton(onClick = onRetryFailed) { Text("Try again") }
            }
        }

        if (queued > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    downloadError ?: "Downloading · $queued chapter${if (queued == 1L) "" else "s"} to go",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (downloadError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (downloadError != null) TextButton(onClick = onResumeDownloads) { Text("Retry") }
                TextButton(onClick = onCancelDownloads) { Text("Cancel") }
            }
        }

        AudioExportRow(book.id)

        if (book.genres.isNotBlank()) {
            Text(book.genres, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        }
        if (book.summary.isNotBlank()) {
            Text(
                book.summary, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (summaryOpen) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
            TextButton(onClick = { summaryOpen = !summaryOpen }, modifier = Modifier.offset(x = (-12).dp)) {
                Text(if (summaryOpen) "Less" else "More")
            }
        }

        if (book.inLibrary && collections.isNotEmpty()) {
            Text("COLLECTIONS", style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                collections.forEach { c ->
                    val inIt = c.id in book.collectionIds
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
