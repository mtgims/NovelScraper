package com.novelscraper.app.ui.screen

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.ChapterListItem
import com.novelscraper.app.data.CollectionRead
import com.novelscraper.app.data.ReadingProgressRead
import com.novelscraper.app.net.Net
import com.novelscraper.app.ui.BookState
import com.novelscraper.app.ui.BookViewModel
import com.novelscraper.app.ui.theme.Kicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    bookId: Int,
    onBack: () -> Unit,
    onOpenReader: (position: Int) -> Unit,
) {
    BackHandler(onBack = onBack)
    val vm: BookViewModel = viewModel()
    LaunchedEffect(bookId) { vm.ensureLoaded(bookId); vm.refreshProgress(bookId) }
    val state by vm.state.collectAsState()
    val collections by vm.collections.collectAsState()

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
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is BookState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is BookState.Error -> Text(s.message, Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error)
                is BookState.Data -> BookContent(s, collections, vm::toggleCollection, onOpenReader)
            }
        }
    }
}

private sealed interface TocRow {
    data class VolumeHead(val number: Int) : TocRow
    data class ChapterItem(val ch: ChapterListItem) : TocRow
}

private fun buildToc(chapters: List<ChapterListItem>): List<TocRow> = buildList {
    var last = -1
    for (ch in chapters) {
        if (ch.volume != last) { last = ch.volume; add(TocRow.VolumeHead(ch.volume)) }
        add(TocRow.ChapterItem(ch))
    }
}

@Composable
private fun BookContent(
    data: BookState.Data,
    collections: List<CollectionRead>,
    onToggleCollection: (Int) -> Unit,
    onOpenReader: (Int) -> Unit,
) {
    val readSet = data.progress?.read_positions?.toSet() ?: emptySet()
    val resumePos = data.progress?.last_position?.takeIf { it > 0 } ?: 1
    val hasProgress = (data.progress?.read_count ?: 0) > 0
    val rows = remember(data.chapters) { buildToc(data.chapters) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BookHeader(data.book, data.progress, hasProgress, resumePos,
                collections, readSet.isNotEmpty(), onToggleCollection, onOpenReader)
        }
        items(
            rows,
            key = { r -> when (r) {
                is TocRow.VolumeHead -> "vol-${r.number}"; is TocRow.ChapterItem -> "ch-${r.ch.position}" } },
        ) { row ->
            when (row) {
                is TocRow.VolumeHead -> Text(
                    "VOLUME ${row.number}",
                    style = Kicker.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                )
                is TocRow.ChapterItem -> ChapterRow(row.ch, row.ch.position in readSet) {
                    onOpenReader(row.ch.position)
                }
            }
        }
        item { Box(Modifier.height(24.dp)) }
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
    started: Boolean,
    onToggleCollection: (Int) -> Unit,
    onOpenReader: (Int) -> Unit,
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
                        model = ImageRequest.Builder(LocalContext.current)
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

@Composable
private fun ChapterRow(ch: ChapterListItem, read: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ch.title.ifBlank { "Chapter ${ch.number}" },
            style = MaterialTheme.typography.bodyLarge,
            color = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (read) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Read",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp).size(18.dp))
        }
    }
}
