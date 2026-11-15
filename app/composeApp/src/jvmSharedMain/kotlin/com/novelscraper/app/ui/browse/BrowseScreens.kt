package com.novelscraper.app.ui.browse

import com.novelscraper.app.extensions.SiteChecks
import com.novelscraper.app.platform.WebPages
import com.novelscraper.app.platform.hasInAppBrowser
import androidx.compose.foundation.background
import com.novelscraper.app.ui.screen.cardColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import com.novelscraper.app.ui.components.MenuItem
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.data.Sentences
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.extensions.ExtensionUpdates
import com.novelscraper.app.extensions.InstalledPlugin
import com.novelscraper.app.extensions.RepoPlugin
import com.novelscraper.app.platform.PlatformBackHandler
import com.novelscraper.app.platform.openInBrowser
import com.novelscraper.app.platform.showToast
import com.novelscraper.app.ui.components.listedCover
import com.novelscraper.app.ui.components.RemoteImage
import com.novelscraper.app.ui.components.ContentWidth
import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.ui.components.ScreenTitle
import com.novelscraper.app.ui.theme.Kicker
import com.novelscraper.app.ui.theme.Serif
import kotlinx.coroutines.launch
import java.util.Locale

// --- shared bits ---------------------------------------------------------------------

@Composable
private fun Centered(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }

@Composable
private fun ErrorText(message: String, onOpenCheck: (() -> Unit)? = null, onRetry: (() -> Unit)? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Row {
            if (onOpenCheck != null) TextButton(onClick = onOpenCheck) { Text("Open the check") }
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** Repository indexes give the language as a name ("English", "Русский");
 *  a short code ("en") is turned into its name. */
private fun languageName(lang: String): String {
    val name = if (lang.length <= 3) {
        runCatching { Locale.forLanguageTag(lang).getDisplayLanguage(Locale.ENGLISH) }.getOrNull()?.ifBlank { null } ?: lang
    } else lang
    return name.replaceFirstChar { it.titlecase() }
}

/** English and the device's language first, then alphabetical. */
private fun languageRank(lang: String): Int {
    val name = languageName(lang).lowercase()
    val device = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).lowercase()
    val native = Locale.getDefault().getDisplayLanguage(Locale.getDefault()).lowercase()
    return when (name) { device, native -> 0; "english" -> 1; else -> 2 }
}

// --- Browse (tab): installed sources ----------------------------------------------------

/** Below this Browse's title actions are icons; the labelled ones need about this much beside the title. */
private val LABELLED_ACTIONS = 600.dp

@Composable
fun BrowseScreen(
    onOpenSource: (String) -> Unit,
    onManage: () -> Unit,
    onSearchAll: () -> Unit = {},
    onMigrate: () -> Unit = {},
) {
    val installed by Extensions.installed.collectAsState()
    val updates by ExtensionUpdates.offers.collectAsState()
    // Opening Browse after a few hours asks the repositories again.
    LaunchedEffect(Unit) { ExtensionUpdates.checkQuietly() }
    Column(Modifier.fillMaxSize()) {
        // On a phone two labelled buttons leave the title no room: Extensions
        // becomes its icon (the update count rides on it) and Migrate, seldom
        // wanted, goes into a menu.
        BoxWithConstraints {
            val narrow = maxWidth < LABELLED_ACTIONS
            var menuOpen by remember { mutableStateOf(false) }
            ScreenTitle("Browse") {
                if (narrow) {
                    IconButton(onClick = onManage) {
                        com.novelscraper.app.ui.components.Badged(updates.size, "Extensions") {
                            Icon(Icons.Filled.Extension, contentDescription = "Extensions")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            MenuItem("Migrate novels", Icons.Outlined.SwapHoriz) { menuOpen = false; onMigrate() }
                        }
                    }
                } else {
                    TextButton(onClick = onMigrate) {
                        Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Migrate", modifier = Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = onManage) {
                        com.novelscraper.app.ui.components.Badged(updates.size, "Extensions") {
                            Icon(Icons.Filled.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Text("Extensions", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        if (installed.isEmpty()) {
            Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No sources yet.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Sources are extensions: add some to browse and read novels from their sites.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 14.dp).widthIn(max = 420.dp),
                    )
                    Button(onClick = onManage) { Text("Add sources") }
                }
            }
        } else {
            ContentWidth {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = if (isDesktop) 24.dp else 104.dp),
                ) {
                    if (updates.isNotEmpty()) item(key = "updates") {
                        UpdatesBanner(updates.map { it.name }, onManage)
                    }
                    item(key = "search-all") {
                        // Looks like a search box, and opens the search of every source.
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(onClick = onSearchAll)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Search all sources", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    items(installed, key = { it.id }) { p ->
                        SourceRow(
                            p.name, p.lang, p.iconUrl,
                            subtitle = p.site.removePrefix("https://").removePrefix("www.").trimEnd('/'),
                            onClick = { onOpenSource(p.id) },
                        ) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    name: String,
    lang: String,
    iconUrl: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceIcon(name, iconUrl, 40.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${languageName(lang)} · $subtitle", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** A source's icon, or its initial where it has none. */
@Composable
internal fun SourceIcon(name: String, iconUrl: String, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 5)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        RemoteImage(iconUrl, emptyMap(), null, Modifier.fillMaxSize())
    }
}

/** Sources with a newer version waiting, named, with the way to update them. */
@Composable
private fun UpdatesBanner(names: List<String>, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Extension, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                if (names.size == 1) "An update for ${names[0]}" else "Updates for ${names.size} sources",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (names.size > 1) Text(
                names.joinToString(", "),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        TextButton(onClick = onOpen) { Text("Update") }
    }
}

// --- Extensions: install / update / remove ------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(onBack: () -> Unit) {
    PlatformBackHandler(onBack = onBack)
    val vm: ExtensionsViewModel = viewModel { ExtensionsViewModel() }
    val ui by vm.ui.collectAsState()
    val installed by vm.installed.collectAsState()
    val repos by vm.repos.collectAsState()
    val synced by vm.synced.collectAsState()
    var tab by rememberSaveable { mutableStateOf(if (Extensions.installed.value.isEmpty()) 1 else 0) }
    var query by rememberSaveable { mutableStateOf("") }
    var lang by rememberSaveable { mutableStateOf<String?>(null) }
    var showRepos by remember { mutableStateOf(false) }

    LaunchedEffect(ui.message) { ui.message?.let { showToast(it); vm.clearMessage() } }

    // Installing a source another device has can mean trusting a repository
    // this one has never listed, and that address arrived in a sync record
    // rather than from the reader. Name it before taking code from it.
    ui.confirmRepo?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.cancelSyncedRepo() },
            title = { Text("Add this repository?") },
            text = {
                Text(
                    "${pending.id} comes from a repository this device does not have:\n\n" +
                        "${pending.repo}\n\n" +
                        "Sources from it run on this device. Add it only if that address is one you trust.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirmSyncedRepo() }) { Text("Add and install") } },
            dismissButton = { TextButton(onClick = { vm.cancelSyncedRepo() }) { Text("Cancel") } },
        )
    }

    val offered = remember(ui.available) { ui.available.associateBy { it.id } }
    // Keyed by the installed plugin, not by id: the offer has to come from the
    // repository that plugin was installed from.
    val updateOffers = remember(ui.available, installed) {
        installed.associate { it.id to Extensions.updateFor(it, ui.available) }
    }
    val updates = installed.filter { updateOffers[it.id] != null }
    val installedIds = installed.mapTo(HashSet()) { it.id }
    val languages = remember(ui.available) {
        ui.available.map { it.lang }.distinct().sortedWith(compareBy({ languageRank(it) }, { languageName(it) }))
    }
    fun matches(name: String, site: String, l: String) =
        (query.isBlank() || name.contains(query, true) || site.contains(query, true)) && (lang == null || l == lang)

    val installedLabel = when (updates.size) { 0 -> "Installed"; 1 -> "Installed (1 update)"; else -> "Installed (${updates.size} updates)" }
    val search: @Composable (Modifier) -> Unit = { m ->
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search sources") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            modifier = m,
        )
    }
    val languageChips: @Composable (Modifier) -> Unit = { m ->
        if (tab == 1 && languages.size > 1) {
            Row(m.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(lang == null, { lang = null }, label = { Text("All") })
                languages.forEach { l -> FilterChip(lang == l, { lang = if (lang == l) null else l }, label = { Text(languageName(l)) }) }
            }
        }
    }
    val failed: @Composable () -> Unit = {
        ui.failedRepos.forEach {
            Text("Couldn't load $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A window this wide gets the computer's layout: one toolbar, and the
        // sources as cards in a grid rather than a phone's list stretched across.
        val wide = maxWidth >= EXTENSIONS_WIDE
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Extensions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { showRepos = true }) { Text("Repositories") }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Refresh") }
                },
            )
            if (wide) {
                ContentWidth(max = EXTENSIONS_MAX_WIDTH) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TabPills(listOf(installedLabel, "Available"), tab) { tab = it }
                            Spacer(Modifier.weight(1f).widthIn(min = 16.dp))
                            search(Modifier.width(340.dp))
                        }
                        languageChips(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        failed()
                    }
                }
            } else {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text(installedLabel) })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Available") })
                }
                search(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                languageChips(Modifier.padding(horizontal = 12.dp))
                failed()
            }
            val spinner: @Composable () -> Unit = { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
            when {
                tab == 0 -> {
                    val list = installed.filter { matches(it.name, it.site, it.lang) }.map { p ->
                        val offer = updateOffers[p.id]
                        SourceEntry(p.id, p.name, p.lang, p.iconUrl, "v${p.version}", p.site) { cards ->
                            if (p.id in ui.busy) spinner()
                            else Row(verticalAlignment = Alignment.CenterVertically) {
                                if (offer != null) {
                                    if (cards) FilledTonalButton(onClick = { vm.install(offer) }) { Text("Update") }
                                    else TextButton(onClick = { vm.install(offer) }) { Text("Update") }
                                }
                                TextButton(onClick = { vm.uninstall(p) }) { Text("Remove") }
                            }
                        }
                    }
                    if (list.isEmpty()) Centered { Text("Nothing installed yet. Pick sources under Available.") }
                    else SourceList(listOf(null to list), wide)
                }
                repos.isEmpty() -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No repositories yet.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Sources come from extension repositories. Add one by its address " +
                                "(the URL of its index.json), then install the sources you want.",
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp).widthIn(max = 460.dp),
                        )
                        Button(onClick = { showRepos = true }) { Text("Add a repository") }
                    }
                }
                ui.loading && ui.available.isEmpty() -> Centered { CircularProgressIndicator() }
                else -> {
                    val install: @Composable (Boolean, () -> Unit) -> Unit = { cards, onClick ->
                        if (cards) FilledTonalButton(onClick = onClick) { Text("Install") }
                        else TextButton(onClick = onClick) { Text("Install") }
                    }
                    // Keyed by repository too: the same id may now legitimately
                    // be offered by two, and a repeated key crashes the list.
                    val list = ui.available.filter { it.id !in installedIds && matches(it.name, it.site, it.lang) }
                        .sortedWith(compareBy({ languageRank(it.lang) }, { it.name.lowercase() }))
                        .map { p ->
                            SourceEntry("${p.id}@${p.repo}", p.name, p.lang, p.iconUrl, "v${p.version}", p.site) { cards ->
                                if (p.id in ui.busy) spinner() else install(cards) { vm.install(p) }
                            }
                        }
                    // Sources another device has whose repository this one does not
                    // list, so they appear nowhere else. Installing one adds the
                    // repository first.
                    val elsewhere = synced.filter { (id, _) -> offered[id] == null && matches(id, id, "") }
                        .map { (id, repo) ->
                            SourceEntry("synced-$id", id, "", "", repo, "") { cards ->
                                if (id in ui.busy) spinner() else install(cards) { vm.installSynced(id, repo) }
                            }
                        }
                    SourceList(
                        listOfNotNull(elsewhere.takeIf { it.isNotEmpty() }?.let { "On your other devices" to it }, null to list),
                        wide,
                    )
                }
            }
        }
    }

    if (showRepos) RepositoriesDialog(repos, onAdd = vm::addRepo, onRemove = vm::removeRepo, onDismiss = { showRepos = false })
}

private val EXTENSIONS_WIDE = 840.dp
private val EXTENSIONS_MAX_WIDTH = 1100.dp

/** One source as the Extensions screen lists it; [actions] are told whether they sit on a card. */
private class SourceEntry(
    val key: String,
    val name: String,
    val lang: String,
    val iconUrl: String,
    val detail: String,
    val site: String,
    val actions: @Composable (cards: Boolean) -> Unit,
)

/** [sections] as a phone's list, or on a wide window as a grid of cards; a section may have a title. */
@Composable
private fun SourceList(sections: List<Pair<String?, List<SourceEntry>>>, wide: Boolean) {
    if (wide) {
        ContentWidth(max = EXTENSIONS_MAX_WIDTH) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                sections.forEach { (title, entries) ->
                    if (title != null) item(key = "title-$title", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                    gridItems(entries, key = { it.key }) { e -> SourceCard(e) }
                }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = 104.dp)) {
            sections.forEachIndexed { i, (title, entries) ->
                if (title != null) item(key = "title-$title") {
                    Text(
                        title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp),
                    )
                }
                items(entries, key = { it.key }) { e -> SourceRow(e.name, e.lang, e.iconUrl, e.detail, null) { e.actions(false) } }
                if (title != null && i < sections.lastIndex) item(key = "divider-$i") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            }
        }
    }
}

/** A source as a card: its icon, name, language and version, and its site, with what can be done to it. */
@Composable
private fun SourceCard(e: SourceEntry) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceIcon(e.name, e.iconUrl, 44.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                Text(
                    e.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(languageName(e.lang).takeIf { e.lang.isNotBlank() }, e.detail).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Always a line, blank or not, so the cards in a row stand the same height.
                Text(
                    siteHost(e.site), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            e.actions(true)
        }
    }
}

/** "https://www.example.com/" as "example.com". */
private fun siteHost(site: String): String =
    site.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

/** Two or more choices as pills in a track, the chosen one filled. */
@Composable
private fun TabPills(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.surfaceContainerHigh).padding(4.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label, style = MaterialTheme.typography.labelLarge,
                color = if (on) c.onPrimaryContainer else c.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) c.primaryContainer else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun RepositoriesDialog(
    repos: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repositories") },
        text = {
            Column {
                Text("Extension repositories: the address of an index.json in LNReader's format.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                repos.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text(r,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onRemove(r) }) { Text("Remove") }
                    }
                }
                OutlinedTextField(url, { url = it }, singleLine = true, placeholder = { Text("https://…/index.json") },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(url); url = "" }, enabled = url.startsWith("http")) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

// --- A source: popular / latest / search ---------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(
    pluginId: String,
    onBack: () -> Unit,
    onOpenNovel: (bookId: Int) -> Unit,
    /** Opened from a search of every source: this source's results for it. */
    initialQuery: String? = null,
) {
    PlatformBackHandler(onBack = onBack)
    val vm: SourceViewModel = viewModel(key = "source-$pluginId-${initialQuery.orEmpty()}") {
        SourceViewModel(pluginId, initialQuery)
    }
    val ui by vm.ui.collectAsState()
    var query by rememberSaveable { mutableStateOf(ui.query) }
    val grid = rememberLazyGridState()

    // Load the next page as the grid nears its end.
    val nearEnd by remember { derivedStateOf {
        val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        last >= grid.layoutInfo.totalItemsCount - 6
    } }
    LaunchedEffect(nearEnd, ui.items.size) { if (nearEnd && ui.items.isNotEmpty()) vm.loadMore() }
    // A new list (popular / latest / another search) starts at the top.
    LaunchedEffect(ui.mode, ui.query) { grid.scrollToItem(0) }
    val installed = remember(pluginId) { Extensions.installed.value.firstOrNull { it.id == pluginId } }
    val site = installed?.site?.ifBlank { null }
    // A check that stopped the list, opened where the reader can pass it.
    val openCheck: (() -> Unit)? = ui.checkUrl?.takeIf { hasInAppBrowser }?.let { url -> { WebPages.openCheck(url) } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(ui.name.ifBlank { installed?.name ?: "Source" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                site?.let { url ->
                    IconButton(onClick = { WebPages.open(url) }) { Icon(Icons.Filled.OpenInBrowser, "Open website") }
                }
            },
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search this source") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) vm.show(SourceMode.Search, query) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && query.isNotBlank()) {
                        vm.show(SourceMode.Search, query); true
                    } else false
                },
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(ui.mode == SourceMode.Popular, { vm.show(SourceMode.Popular) }, label = { Text("Popular") })
            FilterChip(ui.mode == SourceMode.Latest, { vm.show(SourceMode.Latest) }, label = { Text("Latest") })
            if (ui.mode == SourceMode.Search) FilterChip(true, {}, label = { Text("Results for “${ui.query}”") })
        }
        when {
            ui.items.isEmpty() && ui.loading -> Centered { CircularProgressIndicator() }
            ui.items.isEmpty() && ui.error != null -> Centered { ErrorText(ui.error!!, openCheck) { vm.show(ui.mode) } }
            ui.items.isEmpty() && ui.endReached -> Centered { Text("Nothing found.") }
            else -> LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(minSize = 140.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(ui.items, key = { _, it -> it.path }) { _, novel ->
                    Column(Modifier.fillMaxWidth().clickable { vm.open(novel, onOpenNovel) }) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(novel.name.take(2).uppercase(), style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            RemoteImage(listedCover(pluginId, novel), ui.imageHeaders, novel.name, Modifier.fillMaxSize())
                        }
                        Text(novel.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                if (ui.loading || ui.error != null) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            if (ui.loading) CircularProgressIndicator(Modifier.size(28.dp))
                            else ErrorText(ui.error!!, openCheck) { vm.loadMore() }
                        }
                    }
                }
            }
        }
    }
}
