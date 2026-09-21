package com.novelscraper.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.net.Net
import com.novelscraper.app.ui.components.StarRating
import com.novelscraper.app.ui.LibraryPhase
import com.novelscraper.app.ui.LibraryViewModel
import com.novelscraper.app.ui.components.CollectionTabs
import com.novelscraper.app.ui.components.ScreenTitle
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@Composable
fun LibraryScreen(onOpenBook: (Int) -> Unit) {
    val vm: LibraryViewModel = viewModel()
    // Loads on first entry and refreshes on return (e.g. after assigning a book to
    // a collection), keeping current books visible (no loading flash). Deferred
    // past the slide transition so the network result + grid recompose don't land
    // mid-animation (which caused stutter).
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(300); vm.load() }
    val phase by vm.phase.collectAsState()
    val books by vm.books.collectAsState()
    val collections by vm.collections.collectAsState()
    val tab by vm.tab.collectAsState()

    val activeTab = tab
    val display = if (activeTab == null) books else books.filter { it.collection_ids.contains(activeTab) }
    val canReorder = activeTab == null

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        vm.moveBook(from.index, to.index)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Library", action = {
            IconButton(onClick = vm::load) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        })
        CollectionTabs(
                collections = collections,
                activeTab = tab,
                onSelect = vm::selectTab,
                onCreate = vm::createCollection,
                onRename = vm::renameCollection,
                onDelete = vm::deleteCollection,
            )
            Box(Modifier.fillMaxSize()) {
                when (val p = phase) {
                    is LibraryPhase.Loading ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is LibraryPhase.Error ->
                        Text(p.message, Modifier.align(Alignment.Center)
                            .clickable { vm.load() }.padding(24.dp),
                            color = MaterialTheme.colorScheme.error)
                    is LibraryPhase.Ready ->
                        if (display.isEmpty()) {
                            Text(
                                if (tab == null) "Your library is empty." else "Nothing in this collection yet.",
                                Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyLarge,
                            )
                        } else {
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Adaptive(minSize = 150.dp),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 104.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(display, key = { it.id }) { book ->
                                    ReorderableItem(reorderState, key = book.id) { dragging ->
                                        BookCard(
                                            book = book,
                                            modifier = if (canReorder) Modifier.longPressDraggableHandle(
                                                onDragStopped = { vm.commitOrder() },
                                            ) else Modifier,
                                            elevated = dragging,
                                            onClick = { onOpenBook(book.id) },
                                        )
                                    }
                                }
                            }
                        }
                }
            }
        }
    }
@Composable
private fun BookCard(
    book: BookRead,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().scale(if (elevated) 1.03f else 1f).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (book.has_cover) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(Net.coverUrl(book.id)).crossfade(true).build(),
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(book.title.take(2).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(book.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(book.author, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Row always reserved, so rated and unrated cards keep the same height.
        Box(Modifier.height(16.dp).padding(top = 2.dp)) {
            if ((book.rating ?: 0) > 0) StarRating(book.rating, size = 13.dp)
        }
    }
}
