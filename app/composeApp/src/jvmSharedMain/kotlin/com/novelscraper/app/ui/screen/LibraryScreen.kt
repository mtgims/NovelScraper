package com.novelscraper.app.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import com.novelscraper.app.data.LibraryPrefs
import com.novelscraper.app.platform.isDesktop
import androidx.compose.material.icons.filled.FileUpload
import com.novelscraper.app.platform.rememberEpubPicker
import com.novelscraper.app.platform.showToast
import com.novelscraper.app.ui.ImportUi
import com.novelscraper.app.ui.ImportViewModel
import com.novelscraper.app.library.LibBook
import com.novelscraper.app.net.Account
import androidx.compose.foundation.layout.fillMaxHeight
import com.novelscraper.app.ui.components.GridScrollbar
import com.novelscraper.app.ui.components.MenuAction
import com.novelscraper.app.ui.components.WithContextMenu
import com.novelscraper.app.ui.components.BookCover
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import com.novelscraper.app.ui.components.StarRating
import com.novelscraper.app.ui.LibraryViewModel
import com.novelscraper.app.ui.components.CollectionTabs
import com.novelscraper.app.ui.components.ScreenTitle
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@Composable
fun LibraryScreen(onOpenBook: (Int) -> Unit) {
    val vm: LibraryViewModel = viewModel { LibraryViewModel() }
    // New novels on the server (scraped or imported there) come in on entry,
    // deferred past the slide transition so the grid doesn't recompose mid-animation.
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(300); vm.refresh(force = false) }
    val loaded by vm.books.collectAsState()
    val dragOrder by vm.dragOrder.collectAsState()
    val collections by vm.collections.collectAsState()
    val tab by vm.tab.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val updates by vm.updates.collectAsState()
    val account by Account.state.collectAsState()

    val books = loaded.orEmpty().let { list ->
        val order = dragOrder ?: return@let list
        val byId = list.associateBy { it.id }
        order.mapNotNull { byId[it] }
    }
    val activeTab = tab
    val display = if (activeTab == null) books else books.filter { it.collectionIds.contains(activeTab) }
    val canReorder = activeTab == null

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        vm.moveBook(from.index, to.index)
    }

    Column(Modifier.fillMaxSize()) {
        val coverWidth by LibraryPrefs.coverWidth.collectAsState()
        // Importing an EPUB used to live under Scrape, beside pasting a novel's
        // web address. That way of adding a novel is gone, but bringing one in
        // from a file is not, and the library is where it belongs.
        val importVm: ImportViewModel = viewModel { ImportViewModel() }
        val importUi by importVm.ui.collectAsState()
        val pickEpubs = rememberEpubPicker { files -> importVm.importEpubs(files) }
        LaunchedEffect(importUi) {
            when (val ui = importUi) {
                is ImportUi.Done -> { showToast("Imported ${'$'}{ui.book.title}."); importVm.reset(); vm.refresh() }
                is ImportUi.Error -> { showToast(ui.message, long = true); importVm.reset() }
                else -> Unit
            }
        }
        ScreenTitle("Library", action = {
            if (Account.signedIn) {
                if (importUi is ImportUi.Uploading) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                    }
                } else IconButton(onClick = pickEpubs) {
                    Icon(Icons.Filled.FileUpload, contentDescription = "Import an EPUB")
                }
            }
            if (isDesktop) {
                IconButton(onClick = { LibraryPrefs.smaller() }, enabled = LibraryPrefs.canShrink) {
                    Icon(Icons.Filled.Remove, contentDescription = "Smaller covers")
                }
                IconButton(onClick = { LibraryPrefs.bigger() }, enabled = LibraryPrefs.canGrow) {
                    Icon(Icons.Filled.Add, contentDescription = "Bigger covers")
                }
            }
            // The refresh keeps its place whether it is a button or a spinner.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (refreshing || updates.running) CircularProgressIndicator(Modifier.size(22.dp))
                else IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Check for new chapters")
                }
            }
        })
        if (updates.running) {
            Text(
                "Checking for new chapters · ${updates.done + 1} of ${updates.total}" +
                    if (updates.novel.isNotBlank()) " · ${updates.novel}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        CollectionTabs(
                collections = collections,
                activeTab = tab,
                onSelect = vm::selectTab,
                onCreate = vm::createCollection,
                onRename = vm::renameCollection,
                onDelete = vm::deleteCollection,
            )
            Box(Modifier.fillMaxSize()) {
                when {
                    // Also while the server's library is being brought in for the first time.
                    loaded == null || (books.isEmpty() && refreshing) ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    display.isEmpty() -> Text(
                        if (tab != null) "Nothing in this collection yet."
                        else "Your library is empty. Find novels in Browse and add them here.",
                        Modifier.align(Alignment.Center).padding(24.dp), style = MaterialTheme.typography.bodyLarge,
                    )
                    else -> LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = coverWidth.dp),
                        // The pill floats over the bottom of a phone screen; a
                        // rail sits beside the window and needs no room here.
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 6.dp,
                            bottom = if (isDesktop) 24.dp else 104.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize().padding(end = if (isDesktop) 10.dp else 0.dp),
                    ) {
                        items(display, key = { it.id }) { book ->
                            ReorderableItem(reorderState, key = book.id) { dragging ->
                                val menu = listOf(
                                    MenuAction("Open") { onOpenBook(book.id) },
                                    MenuAction("Download all chapters") {
                                        vm.downloadAll(book.id)
                                        showToast("Downloading ${'$'}{book.title}…")
                                    },
                                    MenuAction("Mark all read") { vm.markAllRead(book.id) },
                                    MenuAction("Remove downloads") { vm.removeDownloads(book.id) },
                                    MenuAction("Remove from library") { vm.removeFromLibrary(book.id) },
                                )
                                BookCard(
                                    menu = menu,
                                    book = book,
                                    modifier = when {
                                        !canReorder -> Modifier
                                        // Press and drag with a pointer; hold
                                        // first with a finger, where a plain drag
                                        // is how the grid is scrolled.
                                        isDesktop -> Modifier.draggableHandle(
                                            onDragStopped = { vm.commitOrder() },
                                        )
                                        else -> Modifier.longPressDraggableHandle(
                                            onDragStopped = { vm.commitOrder() },
                                        )
                                    },
                                    elevated = dragging,
                                    onClick = { onOpenBook(book.id) },
                                )
                            }
                        }
                    }
                }
                GridScrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }

@Composable
private fun BookCard(
    book: LibBook,
    menu: List<MenuAction>,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The tile is what the pointer is over, so the tile is what lights up: cover,
    // title and all, inside the same rounded shape as everything else. It used to
    // be a square patch behind a cover that had grown out of it.
    val shape = RoundedCornerShape(14.dp)
    val background by animateColorAsState(
        when {
            elevated -> MaterialTheme.colorScheme.secondaryContainer
            hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
            else -> Color.Transparent
        },
        label = "tile",
    )
    WithContextMenu(
        actions = menu,
        modifier = modifier
            .clip(shape)
            .background(background)
            .hoverable(interaction),
        onClick = onClick,
    ) {
        Column(Modifier.fillMaxWidth().padding(6.dp)) {
            Box {
                BookCover(book, Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp)))
                // Unread chapters, and a mark when chapters are downloaded.
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (book.unreadCount > 0) Badge(book.unreadCount.toString())
                    if (book.downloadedCount > 0) Badge(null)
                }
            }
            Text(
                book.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                book.author.ifBlank { book.site }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // Row always reserved, so rated and unrated cards keep the same height.
            Box(Modifier.height(16.dp).padding(top = 2.dp)) {
                if ((book.rating ?: 0) > 0) StarRating(book.rating, size = 13.dp)
            }
        }
    }
}

/** A small pill on a cover: a count, or (null) the downloaded mark. */
@Composable
private fun Badge(text: String?) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(
            if (text != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
        ).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        if (text != null) Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
        else Icon(Icons.Filled.DownloadDone, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.onTertiary,
            modifier = Modifier.size(14.dp))
    }
}
