package com.novelscraper.app.ui.browse

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.novelscraper.app.extensions.InstalledPlugin
import com.novelscraper.app.extensions.RepoPlugin
import com.novelscraper.app.platform.PlatformBackHandler
import com.novelscraper.app.platform.openInBrowser
import com.novelscraper.app.platform.showToast
import com.novelscraper.app.ui.components.RemoteImage
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
private fun ErrorText(message: String, onRetry: (() -> Unit)? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Try again") }
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

@Composable
fun BrowseScreen(onOpenSource: (String) -> Unit, onManage: () -> Unit) {
    val installed by Extensions.installed.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Browse") {
            TextButton(onClick = onManage) {
                Icon(Icons.Filled.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Extensions", modifier = Modifier.padding(start = 6.dp))
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
            LazyColumn(contentPadding = PaddingValues(bottom = 104.dp)) {
                items(installed, key = { it.id }) { p ->
                    SourceRow(p.name, p.lang, p.iconUrl, subtitle = p.site.removePrefix("https://").removePrefix("www.").trimEnd('/'),
                        onClick = { onOpenSource(p.id) }) {}
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
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            RemoteImage(iconUrl, emptyMap(), null, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${languageName(lang)} · $subtitle", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
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
    var tab by rememberSaveable { mutableStateOf(if (Extensions.installed.value.isEmpty()) 1 else 0) }
    var query by rememberSaveable { mutableStateOf("") }
    var lang by rememberSaveable { mutableStateOf<String?>(null) }
    var showRepos by remember { mutableStateOf(false) }

    LaunchedEffect(ui.message) { ui.message?.let { showToast(it); vm.clearMessage() } }

    val offered = remember(ui.available) { ui.available.associateBy { it.id } }
    val updates = installed.filter { Extensions.hasUpdate(it, offered[it.id]) }
    val installedIds = installed.mapTo(HashSet()) { it.id }
    val languages = remember(ui.available) {
        ui.available.map { it.lang }.distinct().sortedWith(compareBy({ languageRank(it) }, { languageName(it) }))
    }
    fun matches(name: String, site: String, l: String) =
        (query.isBlank() || name.contains(query, true) || site.contains(query, true)) && (lang == null || l == lang)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Extensions") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                TextButton(onClick = { showRepos = true }) { Text("Repositories") }
                IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Refresh") }
            },
        )
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(if (updates.isEmpty()) "Installed" else "Installed (${updates.size} updates)") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Available") })
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search sources") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (tab == 1 && languages.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(lang == null, { lang = null }, label = { Text("All") })
                languages.forEach { l -> FilterChip(lang == l, { lang = if (lang == l) null else l }, label = { Text(languageName(l)) }) }
            }
        }
        ui.failedRepos.forEach {
            Text("Couldn't load $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        }
        when {
            tab == 0 -> {
                val list = installed.filter { matches(it.name, it.site, it.lang) }
                if (list.isEmpty()) Centered { Text("Nothing installed yet. Pick sources under Available.") }
                else LazyColumn(contentPadding = PaddingValues(bottom = 104.dp)) {
                    items(list, key = { it.id }) { p ->
                        val offer = offered[p.id]
                        SourceRow(p.name, p.lang, p.iconUrl, "v${p.version}", null) {
                            if (p.id in ui.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else Row {
                                if (offer != null && Extensions.hasUpdate(p, offer)) TextButton(onClick = { vm.install(offer) }) { Text("Update") }
                                TextButton(onClick = { vm.uninstall(p) }) { Text("Remove") }
                            }
                        }
                    }
                }
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
                val list = ui.available.filter { it.id !in installedIds && matches(it.name, it.site, it.lang) }
                    .sortedWith(compareBy({ languageRank(it.lang) }, { it.name.lowercase() }))
                LazyColumn(contentPadding = PaddingValues(bottom = 104.dp)) {
                    items(list, key = { it.id }) { p ->
                        SourceRow(p.name, p.lang, p.iconUrl, "v${p.version}", null) {
                            if (p.id in ui.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else TextButton(onClick = { vm.install(p) }) { Text("Install") }
                        }
                    }
                }
            }
        }
    }

    if (showRepos) RepositoriesDialog(repos, onAdd = vm::addRepo, onRemove = vm::removeRepo, onDismiss = { showRepos = false })
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
fun SourceScreen(pluginId: String, onBack: () -> Unit, onOpenNovel: (bookId: Int) -> Unit) {
    PlatformBackHandler(onBack = onBack)
    val vm: SourceViewModel = viewModel(key = "source-$pluginId") { SourceViewModel(pluginId) }
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

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(ui.name.ifBlank { "Source" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
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
            ui.items.isEmpty() && ui.error != null -> Centered { ErrorText(ui.error!!) { vm.show(ui.mode) } }
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
                            RemoteImage(novel.cover, ui.imageHeaders, novel.name, Modifier.fillMaxSize())
                        }
                        Text(novel.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                if (ui.loading || ui.error != null) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            if (ui.loading) CircularProgressIndicator(Modifier.size(28.dp))
                            else ErrorText(ui.error!!) { vm.loadMore() }
                        }
                    }
                }
            }
        }
    }
}
