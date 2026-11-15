package com.novelscraper.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.extensions.InstalledPlugin
import com.novelscraper.app.extensions.NovelItem
import com.novelscraper.app.extensions.SiteChallengeException
import com.novelscraper.app.extensions.SiteChecks
import com.novelscraper.app.library.BackupNovel
import com.novelscraper.app.library.Library
import com.novelscraper.app.platform.PlatformBackHandler
import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.platform.showToast
import com.novelscraper.app.ui.components.RemoteImage
import com.novelscraper.app.ui.components.listedCover
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One source's part of a search of every source. */
data class SourceResults(
    val pluginId: String,
    val name: String,
    val iconUrl: String = "",
    val imageHeaders: Map<String, String> = emptyMap(),
    val state: State = State.Searching,
) {
    sealed interface State {
        data object Waiting : State
        data object Searching : State
        data class Found(val items: List<NovelItem>) : State
        /** [check]: the site wants a browser check, which only asking it alone can offer. */
        data class Failed(val message: String, val check: Boolean = false, val url: String? = null) : State
    }
}

/**
 * Sources that found something first, in the order they answered, then those
 * still at it, then those that found nothing, then those that failed. A source
 * that has shown its results keeps its place as the others come in, so a row
 * doesn't move out from under a tap.
 */
internal fun orderResults(rows: List<SourceResults>): List<SourceResults> = rows.sortedBy {
    when (val s = it.state) {
        is SourceResults.State.Found -> if (s.items.isNotEmpty()) 0 else 2
        SourceResults.State.Searching, SourceResults.State.Waiting -> 1
        is SourceResults.State.Failed -> 3
    }
}

/**
 * A search of every installed source at once, a few at a time.
 *
 * Each source asks its site unattended: a site behind a browser check says so
 * in its row, and no window or prompt comes up for a source nobody picked.
 * Searches run when asked (the search key), never per keystroke: some sites
 * refuse a second search within seconds.
 */
class GlobalSearchViewModel(initialQuery: String, private val exclude: String?) : ViewModel() {
    private val _rows = MutableStateFlow<List<SourceResults>>(emptyList())
    val rows: StateFlow<List<SourceResults>> = _rows.asStateFlow()
    private val _searched = MutableStateFlow("")
    /** What the rows are results for. */
    val searched: StateFlow<String> = _searched.asStateFlow()
    private var job: Job? = null

    init {
        if (initialQuery.isNotBlank()) search(initialQuery)
        // A source's check passed: its row asks again.
        viewModelScope.launch {
            SiteChecks.passed.collect { url ->
                _rows.value.filter { (it.state as? SourceResults.State.Failed)?.let { f -> f.check && SiteChecks.sameSite(f.url, url) } == true }
                    .forEach { retry(it.pluginId) }
            }
        }
    }

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        _searched.value = q
        val sources = Extensions.installed.value.filter { it.id != exclude }
        _rows.value = sources.map { it.toRow(SourceResults.State.Waiting) }
        job = viewModelScope.launch {
            val slots = Semaphore(PARALLEL)
            sources.forEach { p -> launch { slots.withPermit { searchOne(p, q) } } }
        }
    }

    /** Ask one source again (after it failed). */
    fun retry(pluginId: String) {
        val p = Extensions.installed.value.firstOrNull { it.id == pluginId } ?: return
        val q = _searched.value.ifEmpty { return }
        viewModelScope.launch { searchOne(p, q) }
    }

    private suspend fun searchOne(p: InstalledPlugin, q: String) {
        set(p.id, SourceResults.State.Searching)
        val state = try {
            val rt = Extensions.runtime(p.id)
            SourceResults.State.Found(rt.search(q, 1, unattended = true).distinctBy { it.path })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SiteChallengeException) {
            SourceResults.State.Failed("The site wants a browser check.", check = true, url = e.url)
        } catch (e: Exception) {
            SourceResults.State.Failed(describe(e))
        }
        set(p.id, state)
    }

    private fun set(pluginId: String, state: SourceResults.State) = _rows.update { rows ->
        orderResults(rows.map { if (it.pluginId == pluginId) it.copy(state = state) else it })
    }

    private fun InstalledPlugin.toRow(state: SourceResults.State) =
        SourceResults(id, name, iconUrl, imageHeaders, state)

    /** Open a result: its local id (a row is made the first time). */
    fun open(pluginId: String, item: NovelItem, onReady: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                onReady(Library.store.openSource(pluginId, item.path, item.name, item.cover))
            } catch (e: Exception) {
                showToast(describe(e), long = true)
            }
        }
    }

    /** A picked result is being added with a backup's progress; other picks are ignored meanwhile. */
    private val restoring = java.util.concurrent.atomic.AtomicBoolean(false)

    /** A result picked as [novel] from a backup: it joins the library with the
     *  backup's progress (its chapter list fetched first if need be). */
    fun openRestoring(pluginId: String, item: NovelItem, novel: BackupNovel, onReady: (Int) -> Unit) {
        if (!restoring.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val id = Library.store.openSource(pluginId, item.path, item.name, item.cover)
                if (Library.restore.restoreFound(id, novel)) showToast("Reading progress restored for ${novel.title}")
                onReady(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(describe(e), long = true)
            } finally {
                restoring.set(false)
            }
        }
    }

    companion object {
        /** Sources asked at once: as many as the app keeps running. */
        const val PARALLEL = 4
    }
}

/**
 * Search every source. [onPick] is what a result does: open the novel, or (in
 * a migration) choose it. [onOpenSource] opens one source's own search, which
 * is also where a browser check can be answered. [header] goes above the
 * results (a migration says what is being moved).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    initialQuery: String,
    onBack: () -> Unit,
    onPick: (vm: GlobalSearchViewModel, pluginId: String, item: NovelItem) -> Unit,
    onOpenSource: (pluginId: String, query: String) -> Unit,
    title: String = "Search all sources",
    exclude: String? = null,
    vmKey: String = "global-search",
    header: (@Composable () -> Unit)? = null,
) {
    PlatformBackHandler(onBack = onBack)
    val vm: GlobalSearchViewModel = viewModel(key = vmKey) { GlobalSearchViewModel(initialQuery, exclude) }
    val rows by vm.rows.collectAsState()
    val searched by vm.searched.collectAsState()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The keyboard goes once the search starts: the results are what is wanted now.
    val submit = { keyboard?.hide(); vm.search(query) }
    // Only before the first search. Coming back from a novel re-enters this
    // screen, and the keyboard coming up again covered the results being tapped.
    LaunchedEffect(Unit) { if (initialQuery.isBlank() && vm.searched.value.isEmpty()) runCatching { focus.requestFocus() } }

    val installed by Extensions.installed.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("A title, in every source") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { submit(); true } else false
                },
        )
        when {
            installed.none { it.id != exclude } -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text("No other sources are installed. Add some under Browse, Extensions.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            searched.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text("Every installed source is searched at once.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val done = rows.count { it.state is SourceResults.State.Found || it.state is SourceResults.State.Failed }
                LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = if (isDesktop) 24.dp else 104.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    header?.let { h -> item(key = "header") { h() } }
                    item(key = "progress") {
                        Text(
                            if (done < rows.size) "Searching ${rows.size} sources for “$searched”… $done answered"
                            else "${rows.count { (it.state as? SourceResults.State.Found)?.items?.isNotEmpty() == true }} of " +
                                "${rows.size} sources found “$searched”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    items(rows, key = { it.pluginId }) { row ->
                        SourceResultsRow(
                            row,
                            onOpenSource = { onOpenSource(row.pluginId, searched) },
                            onRetry = { vm.retry(row.pluginId) },
                            onPick = { item -> onPick(vm, row.pluginId, item) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceResultsRow(
    row: SourceResults,
    onOpenSource: () -> Unit,
    onRetry: () -> Unit,
    onPick: (NovelItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenSource).padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(row.name.take(1).uppercase(), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                RemoteImage(row.iconUrl, emptyMap(), null, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (note, error) = when (val s = row.state) {
                    SourceResults.State.Waiting -> "Waiting its turn" to false
                    SourceResults.State.Searching -> "Searching…" to false
                    is SourceResults.State.Found ->
                        (if (s.items.isEmpty()) "Nothing found" else "${s.items.size} found") to false
                    is SourceResults.State.Failed -> s.message to true
                }
                Text(note, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (val s = row.state) {
                SourceResults.State.Searching -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                is SourceResults.State.Failed ->
                    if (s.check) TextButton(onClick = onOpenSource) { Text("Open") }
                    else TextButton(onClick = onRetry) { Text("Retry") }
                else -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open this source",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val items = (row.state as? SourceResults.State.Found)?.items.orEmpty()
        if (items.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.path }) { novel ->
                    Column(Modifier.width(112.dp).clip(RoundedCornerShape(10.dp)).clickable { onPick(novel) }) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(novel.name.take(2).uppercase(), style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            RemoteImage(listedCover(row.pluginId, novel), row.imageHeaders, novel.name, Modifier.fillMaxSize())
                        }
                        Text(novel.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp).height(36.dp))
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp, start = 20.dp, end = 20.dp), color = MaterialTheme.colorScheme.outline)
    }
}
