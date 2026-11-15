package com.novelscraper.app.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.extensions.NovelItem
import com.novelscraper.app.extensions.SiteChecks
import com.novelscraper.app.library.ChapterDownloads
import com.novelscraper.app.library.LibBook
import com.novelscraper.app.library.Library
import com.novelscraper.app.library.MatchFinder
import com.novelscraper.app.library.SourceMigration
import com.novelscraper.app.platform.PlatformBackHandler
import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.platform.settingsStore
import com.novelscraper.app.platform.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

// --- Which source to move novels off ----------------------------------------------------

/** A source with novels in the library: installed or not. */
private data class SourceCount(val pluginId: String, val name: String, val count: Int, val installed: Boolean)

/**
 * The sources the library's novels come from, with how many each has. Picking
 * one moves its novels to other sources, all at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrateSourcesScreen(onBack: () -> Unit, onPick: (pluginId: String) -> Unit) {
    PlatformBackHandler(onBack = onBack)
    val installed by Extensions.installed.collectAsState()
    val books by remember { Library.store.libraryFlow() }.collectAsState(null)
    val sources = remember(books, installed) {
        books.orEmpty().filter { it.pluginId != null }.groupBy { it.pluginId!! }.map { (id, list) ->
            val p = installed.firstOrNull { it.id == id }
            SourceCount(id, p?.name ?: list.first().site.ifBlank { id }, list.size, p != null)
        }.sortedWith(compareBy({ it.name.lowercase() }))
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Migrate") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        when {
            books == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            sources.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text("No novel in the library comes from a source.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = if (isDesktop) 24.dp else 104.dp)) {
                item(key = "intro") {
                    Text(
                        "Pick a source to move its novels to others. Each is looked for by its title; " +
                            "nothing moves before you check the matches.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                items(sources, key = { it.pluginId }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(s.pluginId) }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (s.count == 1) "1 novel" else "${s.count} novels") +
                                    (if (s.installed) "" else ", source not installed"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// --- Moving one source's novels -----------------------------------------------------------

/** A source to look on, and whether it is looked on this time. */
data class MigrationTarget(val pluginId: String, val name: String, val on: Boolean)

/** Where one novel stands in a run. */
sealed interface MigrationRow {
    data object Waiting : MigrationRow
    data object Searching : MigrationRow
    data class Ready(val prepared: SourceMigration.Prepared.Ready, val site: String, val ticked: Boolean) : MigrationRow
    data object NoMatch : MigrationRow
    data class Failed(val reason: String) : MigrationRow
    data class Moved(val site: String) : MigrationRow
    data class MoveFailed(val reason: String) : MigrationRow
}

/**
 * Moving the novels of one source to others. Choose the novels and the
 * sources to look on, in order; every novel is looked for, a couple at a time
 * and one search per site at a time; the matches are checked; then the ticked
 * ones move, one after another.
 */
class MigrateFromViewModel(val pluginId: String) : ViewModel() {
    enum class Phase { Choose, Matching, Moving, Done }

    private val prefs = settingsStore("migration")

    private val _books = MutableStateFlow<List<LibBook>?>(null)
    val books: StateFlow<List<LibBook>?> = _books.asStateFlow()
    private val _selected = MutableStateFlow<Set<Int>>(emptySet())
    val selected: StateFlow<Set<Int>> = _selected.asStateFlow()
    private val _targets = MutableStateFlow(loadTargets())
    val targets: StateFlow<List<MigrationTarget>> = _targets.asStateFlow()
    private val _phase = MutableStateFlow(Phase.Choose)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _rows = MutableStateFlow<Map<Int, MigrationRow>>(emptyMap())
    val rows: StateFlow<Map<Int, MigrationRow>> = _rows.asStateFlow()
    private val _unavailable = MutableStateFlow<List<MatchFinder.Unavailable>>(emptyList())
    /** Sources that couldn't be asked; left out for the rest of the run. */
    val unavailable: StateFlow<List<MatchFinder.Unavailable>> = _unavailable.asStateFlow()

    private val lanes = HashMap<String, Mutex>()
    private val lastAsked = HashMap<String, Long>()
    private var matching: Job? = null

    private val engine = SourceMigration(
        store = Library.store,
        finder = MatchFinder { id, title -> paced(id) { Extensions.runtime(id).search(title, 1, unattended = true) } },
        installed = { id -> Extensions.installed.value.any { it.id == id } },
        describe = ::describe,
        gapMs = 0,
        paced = { id, fetch -> paced(id) { fetch() } },
    )

    init {
        // A source left out for its check, passed since: asked again.
        viewModelScope.launch {
            SiteChecks.passed.collect { url ->
                _unavailable.value.filter { it.check && SiteChecks.sameSite(it.url, url) }.forEach { tryAgain(it.pluginId) }
            }
        }
        viewModelScope.launch {
            val list = Library.store.libraryFlowOnce().filter { it.pluginId == pluginId }
            _books.value = list
            _selected.value = list.map { it.id }.toSet()
        }
    }

    fun siteName(id: String) = Extensions.installed.value.firstOrNull { it.id == id }?.name ?: id

    /** One search per site at a time, and a pause between them: some sites refuse a second within seconds. */
    private suspend fun <T> paced(id: String, block: suspend () -> T): T {
        val lane = synchronized(lanes) { lanes.getOrPut(id) { Mutex() } }
        return lane.withLock {
            val wait = (synchronized(lastAsked) { lastAsked[id] } ?: 0L) + GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try { block() } finally { synchronized(lastAsked) { lastAsked[id] = System.currentTimeMillis() } }
        }
    }

    private fun loadTargets(): List<MigrationTarget> {
        val order = prefs.getString("order", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val off = prefs.getStringSet("off").orEmpty()
        val others = Extensions.installed.value.filter { it.id != pluginId }
        val sorted = others.sortedBy { p -> order.indexOf(p.id).let { if (it < 0) Int.MAX_VALUE else it } }
        return sorted.map { MigrationTarget(it.id, it.name, it.id !in off) }
    }

    private fun saveTargets(list: List<MigrationTarget>) {
        _targets.value = list
        val order = prefs.getString("order", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        // Sources not shown here (this one) keep their place after the ones shown.
        prefs.putString("order", (list.map { it.pluginId } + order.filter { id -> list.none { it.pluginId == id } }).joinToString(","))
        val off = prefs.getStringSet("off").orEmpty() - list.map { it.pluginId }.toSet()
        prefs.putStringSet("off", off + list.filter { !it.on }.map { it.pluginId })
    }

    fun toggleTarget(id: String) = saveTargets(_targets.value.map { if (it.pluginId == id) it.copy(on = !it.on) else it })

    fun moveTarget(id: String, by: Int) {
        val list = _targets.value.toMutableList()
        val i = list.indexOfFirst { it.pluginId == id }
        val j = i + by
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        saveTargets(list)
    }

    fun toggleBook(id: Int) = _selected.update { if (id in it) it - id else it + id }
    fun selectAll(all: Boolean) { _selected.value = if (all) _books.value.orEmpty().map { it.id }.toSet() else emptySet() }

    /** Look for every chosen novel. */
    fun findMatches() {
        val chosen = _books.value.orEmpty().filter { it.id in _selected.value }
        val order = _targets.value.filter { it.on }.map { it.pluginId }
        if (chosen.isEmpty() || order.isEmpty()) return
        _phase.value = Phase.Matching
        _unavailable.value = emptyList()
        _rows.value = chosen.associate { it.id to MigrationRow.Waiting }
        matching = viewModelScope.launch {
            val slots = Semaphore(AT_ONCE)
            chosen.forEach { book -> launch { slots.withPermit { look(book, order) } } }
        }
    }

    private suspend fun look(book: LibBook, order: List<String>) {
        // Given a match by hand while waiting its turn: that one stands.
        if (_rows.value[book.id] != MigrationRow.Waiting) return
        set(book.id, MigrationRow.Searching)
        val outcome = engine.prepare(book, order, skip = _unavailable.value.map { it.pluginId }.toSet())
        _unavailable.update { known ->
            known + outcome.unavailable.filter { u -> u.lasting && known.none { it.pluginId == u.pluginId } }
        }
        set(book.id, outcome.prepared.toRow())
    }

    /** Ask a source left out again (its check was passed), for the novels still without a match. */
    fun tryAgain(sourceId: String) {
        if (_phase.value != Phase.Matching) return
        _unavailable.update { list -> list.filter { it.pluginId != sourceId } }
        val order = _targets.value.filter { it.on }.map { it.pluginId }
        val again = _books.value.orEmpty().filter { b ->
            when (_rows.value[b.id]) { MigrationRow.NoMatch, is MigrationRow.Failed -> true; else -> false }
        }
        again.forEach { set(it.id, MigrationRow.Waiting) }
        viewModelScope.launch {
            val slots = Semaphore(AT_ONCE)
            again.forEach { book -> launch { slots.withPermit { look(book, order) } } }
        }
    }

    /** A match picked by hand, for a novel the search missed or got wrong. */
    fun pick(bookId: Int, pluginId: String, item: NovelItem) {
        val book = _books.value?.firstOrNull { it.id == bookId } ?: return
        set(bookId, MigrationRow.Searching)
        viewModelScope.launch { set(bookId, engine.ready(book, pluginId, item, exact = true).toRow()) }
    }

    fun toggleRow(bookId: Int) = _rows.update { rows ->
        val r = rows[bookId] as? MigrationRow.Ready ?: return@update rows
        rows + (bookId to r.copy(ticked = !r.ticked))
    }

    /** Move every ticked novel, one at a time. */
    fun move(keepOld: Boolean, downloadAgain: Boolean) {
        val ready = _rows.value.mapNotNull { (id, r) -> (r as? MigrationRow.Ready)?.takeIf { it.ticked }?.let { id to it } }
        if (ready.isEmpty()) return
        _phase.value = Phase.Moving
        viewModelScope.launch {
            val usedTargets = HashSet<Int>()
            var moved = 0
            for ((id, r) in ready) {
                val p = r.prepared
                if (!usedTargets.add(p.toId)) {
                    set(id, MigrationRow.MoveFailed("Another novel was just moved to the same one."))
                    continue
                }
                try {
                    engine.move(p.fromId, p.toId, keepOld, downloadAgain)
                    set(id, MigrationRow.Moved(r.site))
                    moved++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    set(id, MigrationRow.MoveFailed(describe(e)))
                }
            }
            if (downloadAgain && moved > 0) ChapterDownloads.start()
            showToast(if (moved == 1) "1 novel moved." else "$moved novels moved.")
            _phase.value = Phase.Done
        }
    }

    private fun SourceMigration.Prepared.toRow(): MigrationRow = when (this) {
        is SourceMigration.Prepared.Ready -> MigrationRow.Ready(this, siteName(pluginId), tick)
        SourceMigration.Prepared.NoMatch -> MigrationRow.NoMatch
        is SourceMigration.Prepared.Failed -> MigrationRow.Failed(
            (pluginId?.let { "${siteName(it)}: " } ?: "") + reason)
    }

    private fun set(bookId: Int, row: MigrationRow) = _rows.update { it + (bookId to row) }

    companion object {
        /** Novels looked for at once. */
        const val AT_ONCE = 2
        /** The least time between two searches on one site. */
        const val GAP_MS = 2000L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrateFromScreen(
    pluginId: String,
    onBack: () -> Unit,
    onOpenSource: (pluginId: String, query: String) -> Unit,
) {
    val vm: MigrateFromViewModel = viewModel(key = "migrate-from-$pluginId") { MigrateFromViewModel(pluginId) }
    val books by vm.books.collectAsState()
    val selected by vm.selected.collectAsState()
    val targets by vm.targets.collectAsState()
    val phase by vm.phase.collectAsState()
    val rows by vm.rows.collectAsState()
    val unavailable by vm.unavailable.collectAsState()
    var pickFor by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf(false) }

    val fromName = remember(pluginId, books) {
        Extensions.installed.value.firstOrNull { it.id == pluginId }?.name ?: books?.firstOrNull()?.site ?: pluginId
    }

    // Choosing a match by hand: the search of every source, starting from the title.
    pickFor?.let { id ->
        val book = books?.firstOrNull { it.id == id }
        if (book != null) {
            GlobalSearchScreen(
                initialQuery = book.title,
                onBack = { pickFor = null },
                onPick = { _, plugin, item -> vm.pick(id, plugin, item); pickFor = null },
                onOpenSource = onOpenSource,
                title = "Match for “${book.title}”",
                exclude = pluginId,
                vmKey = "migrate-pick-$id",
            )
            return
        }
    }

    // Held while moving: leaving would stop the run between two novels.
    PlatformBackHandler { if (phase != MigrateFromViewModel.Phase.Moving) onBack() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Migrate from $fromName", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(enabled = phase != MigrateFromViewModel.Phase.Moving, onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
        )
        val list = books
        when {
            list == null -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
            phase == MigrateFromViewModel.Phase.Choose -> ChooseStep(
                list, selected, targets, vm, Modifier.weight(1f),
            )
            else -> MatchStep(
                list.filter { it.id in rows }, rows, unavailable, phase, vm,
                onPick = { if (phase == MigrateFromViewModel.Phase.Matching) pickFor = it },
                onOpenSource = { id -> onOpenSource(id, "") },
                modifier = Modifier.weight(1f),
            )
        }

        // The one action for the step, at the bottom.
        val searching = rows.values.any { it is MigrationRow.Waiting || it is MigrationRow.Searching }
        val ticked = rows.values.count { it is MigrationRow.Ready && it.ticked }
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val note = when (phase) {
                    MigrateFromViewModel.Phase.Choose ->
                        "${selected.size} of ${list?.size ?: 0} novels, " + count(targets.count { it.on }, "source") + " to look on"
                    MigrateFromViewModel.Phase.Matching ->
                        if (searching) "Looking… ${rows.values.count { it !is MigrationRow.Waiting && it !is MigrationRow.Searching }} of ${rows.size} done"
                        else "$ticked ticked to move"
                    MigrateFromViewModel.Phase.Moving -> "Moving…"
                    MigrateFromViewModel.Phase.Done -> "${rows.values.count { it is MigrationRow.Moved }} moved"
                }
                Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                when (phase) {
                    MigrateFromViewModel.Phase.Choose -> Button(
                        enabled = selected.isNotEmpty() && targets.any { it.on },
                        onClick = vm::findMatches,
                    ) { Text("Find matches") }
                    MigrateFromViewModel.Phase.Matching -> Button(
                        enabled = !searching && ticked > 0,
                        onClick = { confirm = true },
                    ) { Text(if (ticked == 0) "Move" else "Move $ticked") }
                    MigrateFromViewModel.Phase.Moving -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    MigrateFromViewModel.Phase.Done -> Button(onClick = onBack) { Text("Done") }
                }
            }
        }
    }

    if (confirm) {
        val ready = rows.values.filterIsInstance<MigrationRow.Ready>().filter { it.ticked }
        MoveDialog(fromName, ready, onDismiss = { confirm = false }) { keepOld, again ->
            confirm = false
            vm.move(keepOld, again)
        }
    }
}

@Composable
private fun ChooseStep(
    books: List<LibBook>,
    selected: Set<Int>,
    targets: List<MigrationTarget>,
    vm: MigrateFromViewModel,
    modifier: Modifier,
) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "novels-head") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Novels to move", Modifier.weight(1f))
                TextButton(onClick = { vm.selectAll(selected.size < books.size) }) {
                    Text(if (selected.size < books.size) "All" else "None")
                }
            }
        }
        items(books, key = { "b${it.id}" }) { b ->
            CheckRow(b.id in selected, { vm.toggleBook(b.id) }) {
                Text(b.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (b.chapterCount == 0) "Chapters not loaded yet"
                    else "${b.chapterCount - b.unreadCount} of ${b.chapterCount} chapters read",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "targets-head") {
            HorizontalDivider(Modifier.padding(top = 12.dp))
            SectionTitle("Look on, in this order", Modifier.padding(start = 20.dp, top = 16.dp))
            Text(
                "The first source with the same title is taken.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (targets.isEmpty()) item(key = "no-targets") {
            Text("No other sources are installed. Add some under Browse, Extensions.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
        items(targets, key = { "t${it.pluginId}" }) { t ->
            val i = targets.indexOf(t)
            CheckRow(t.on, { vm.toggleTarget(t.pluginId) }, trailing = {
                IconButton(enabled = i > 0, onClick = { vm.moveTarget(t.pluginId, -1) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, "Earlier")
                }
                IconButton(enabled = i < targets.lastIndex, onClick = { vm.moveTarget(t.pluginId, 1) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Later")
                }
            }) {
                Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MatchStep(
    books: List<LibBook>,
    rows: Map<Int, MigrationRow>,
    unavailable: List<MatchFinder.Unavailable>,
    phase: MigrateFromViewModel.Phase,
    vm: MigrateFromViewModel,
    onPick: (Int) -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(unavailable, key = { "u${it.pluginId}" }) { u ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${vm.siteName(u.pluginId)} " + (if (u.check) "asks for a browser check" else "couldn't be searched") +
                        ", so it was left out.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (u.check) TextButton(onClick = { onOpenSource(u.pluginId) }) { Text("Open") }
                if (phase == MigrateFromViewModel.Phase.Matching) TextButton(onClick = { vm.tryAgain(u.pluginId) }) { Text("Try again") }
            }
        }
        if (phase == MigrateFromViewModel.Phase.Matching) item(key = "hint") {
            Text(
                "Tap a novel to choose its match by hand.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        items(books, key = { it.id }) { b ->
            val r = rows[b.id] ?: MigrationRow.Waiting
            Row(
                Modifier.fillMaxWidth().clickable(enabled = phase == MigrateFromViewModel.Phase.Matching && r !is MigrationRow.Searching) { onPick(b.id) }
                    .padding(start = 8.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp), Alignment.Center) {
                    when (r) {
                        is MigrationRow.Ready -> if (phase == MigrateFromViewModel.Phase.Matching) Checkbox(
                            checked = r.ticked,
                            enabled = phase == MigrateFromViewModel.Phase.Matching,
                            onCheckedChange = { vm.toggleRow(b.id) },
                        )
                        MigrationRow.Searching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else -> {}
                    }
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(b.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val (line, error) = when (r) {
                        MigrationRow.Waiting -> "Waiting its turn" to false
                        MigrationRow.Searching -> "Looking…" to false
                        is MigrationRow.Ready -> {
                            val p = r.prepared.preview
                            when {
                                // Moving is over and this one was left out.
                                phase != MigrateFromViewModel.Phase.Matching && !r.ticked -> "Left where it is" to false
                                else -> ("${r.site}: “${r.prepared.item.name}”, " + count(p.chaptersThere, "chapter") +
                                    " (${p.chaptersHere} here), ${p.readHere} read here become ${p.readThere}") to false
                            }
                        }
                        MigrationRow.NoMatch -> "Not found on the sources looked on" to true
                        is MigrationRow.Failed -> r.reason to true
                        is MigrationRow.Moved -> "Moved to ${r.site}" to false
                        is MigrationRow.MoveFailed -> "Couldn't move: ${r.reason}" to true
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    (r as? MigrationRow.Ready)?.takeIf { phase == MigrateFromViewModel.Phase.Matching }?.prepared?.let { p ->
                        p.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        p.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveDialog(
    fromName: String,
    ready: List<MigrationRow.Ready>,
    onDismiss: () -> Unit,
    onMove: (keepOld: Boolean, downloadAgain: Boolean) -> Unit,
) {
    var keepOld by remember { mutableStateOf(false) }
    var again by remember { mutableStateOf(true) }
    val downloaded = ready.sumOf { it.prepared.preview.downloadedHere }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (ready.size == 1) "Move 1 novel?" else "Move ${ready.size} novels?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "What has been read, where reading left off, the rating and collections move with each.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                CheckLine("Keep the $fromName versions in the library too", keepOld) { keepOld = it }
                if (!keepOld) Text(
                    "The $fromName versions leave the library" +
                        (if (downloaded > 0) ", with their $downloaded downloaded chapters" else "") +
                        ". Your other devices do the same when they sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (downloaded > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloaded > 0) CheckLine("Download the same chapters from the new sources", again) { again = it }
            }
        },
        confirmButton = { TextButton(onClick = { onMove(keepOld, again && downloaded > 0) }) { Text("Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier)

@Composable
private fun CheckRow(
    checked: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 8.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(start = 4.dp)) { content() }
        trailing?.invoke()
    }
}

@Composable
private fun CheckLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"
