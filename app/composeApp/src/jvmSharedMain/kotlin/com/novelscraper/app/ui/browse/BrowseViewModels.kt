package com.novelscraper.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.extensions.InstalledPlugin
import com.novelscraper.app.extensions.NovelItem
import com.novelscraper.app.extensions.PluginException
import com.novelscraper.app.extensions.RepoPlugin
import com.novelscraper.app.extensions.SiteChallengeException
import com.novelscraper.app.extensions.SourceNovel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What to tell the user when a plugin call fails. */
fun describe(e: Throwable): String = when (e) {
    is SiteChallengeException -> "This site asks for a browser check first, which the app can't pass yet."
    is PluginException -> "The source failed: ${e.message}"
    is kotlinx.coroutines.TimeoutCancellationException -> "The source took too long to answer."
    else -> "Couldn't reach the source (${e.message ?: e.javaClass.simpleName})."
}

/** Headers a source wants on its images (Referer, User-Agent), from the plugin's
 *  imageRequestInit. */
fun imageHeaders(init: JsonObject?): Map<String, String> =
    (init?.get("headers") as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

// --- Extensions (install / update / remove) -------------------------------------------

data class ExtensionsUi(
    val loading: Boolean = false,
    val available: List<RepoPlugin> = emptyList(),
    val failedRepos: List<String> = emptyList(),
    val busy: Set<String> = emptySet(),
    val message: String? = null,
)

class ExtensionsViewModel : ViewModel() {
    private val _ui = MutableStateFlow(ExtensionsUi())
    val ui: StateFlow<ExtensionsUi> = _ui.asStateFlow()
    val installed: StateFlow<List<InstalledPlugin>> = Extensions.installed
    val repos: StateFlow<List<String>> = Extensions.repos

    init { refresh() }

    fun refresh() {
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            val r = Extensions.available()
            _ui.update { it.copy(loading = false, available = r.plugins, failedRepos = r.failed) }
        }
    }

    fun install(p: RepoPlugin) = act(p.id, "Installed ${p.name}.") { Extensions.install(p) }
    fun uninstall(p: InstalledPlugin) = act(p.id, "Removed ${p.name}.") { Extensions.uninstall(p.id) }

    fun addRepo(url: String) { Extensions.addRepo(url); refresh() }
    fun removeRepo(url: String) { Extensions.removeRepo(url); refresh() }
    fun clearMessage() = _ui.update { it.copy(message = null) }

    private fun act(id: String, done: String, block: suspend () -> Unit) {
        if (id in _ui.value.busy) return
        _ui.update { it.copy(busy = it.busy + id) }
        viewModelScope.launch {
            val msg = try { block(); done } catch (e: Exception) { "Couldn't do that: ${describe(e)}" }
            _ui.update { it.copy(busy = it.busy - id, message = msg) }
        }
    }
}

// --- A source's catalogue ---------------------------------------------------------------

enum class SourceMode { Popular, Latest, Search }

data class SourceUi(
    val name: String = "",
    val mode: SourceMode = SourceMode.Popular,
    val query: String = "",
    val items: List<NovelItem> = emptyList(),
    val page: Int = 0,
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    val imageHeaders: Map<String, String> = emptyMap(),
)

class SourceViewModel(private val pluginId: String) : ViewModel() {
    private val _ui = MutableStateFlow(SourceUi())
    val ui: StateFlow<SourceUi> = _ui.asStateFlow()
    private var job: Job? = null

    init { show(SourceMode.Popular) }

    fun show(mode: SourceMode, query: String = _ui.value.query) {
        job?.cancel()
        _ui.update { it.copy(mode = mode, query = query, items = emptyList(), page = 0, endReached = false, error = null) }
        loadMore()
    }

    /** Next page (called as the list nears its end). */
    fun loadMore() {
        val s = _ui.value
        if (s.loading || s.endReached) return
        if (s.mode == SourceMode.Search && s.query.isBlank()) return
        _ui.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val rt = Extensions.runtime(pluginId)
                val next = s.page + 1
                val got = when (s.mode) {
                    SourceMode.Popular -> rt.popular(next, latest = false)
                    SourceMode.Latest -> rt.popular(next, latest = true)
                    SourceMode.Search -> rt.search(s.query.trim(), next)
                }
                // Some sources ignore the page and repeat it: stop when nothing is new.
                val known = s.items.mapTo(HashSet()) { it.path }
                val fresh = got.filter { it.path !in known }
                _ui.update {
                    it.copy(
                        name = rt.info.name, items = it.items + fresh, page = next, loading = false,
                        endReached = fresh.isEmpty(), imageHeaders = imageHeaders(rt.info.imageRequestInit),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = describe(e)) }
            }
        }
    }
}

// --- A novel from a source ---------------------------------------------------------------

/** The novel last opened from a source, so its reader can step through the same
 *  chapter list without fetching it again. */
object SourceSession {
    data class Key(val pluginId: String, val path: String)
    private val chapters = HashMap<Key, List<ChapterItem>>()
    fun put(pluginId: String, path: String, list: List<ChapterItem>) = synchronized(this) {
        chapters.clear(); chapters[Key(pluginId, path)] = list
    }
    fun get(pluginId: String, path: String): List<ChapterItem>? = synchronized(this) { chapters[Key(pluginId, path)] }
}

data class SourceNovelUi(
    val loading: Boolean = true,
    val novel: SourceNovel? = null,
    val chapters: List<ChapterItem> = emptyList(),
    /** Pages of chapters loaded so far, for sources that page their chapter list. */
    val pagesLoaded: Int = 0,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val webUrl: String? = null,
    val imageHeaders: Map<String, String> = emptyMap(),
)

class SourceNovelViewModel(private val pluginId: String, private val path: String) : ViewModel() {
    private val _ui = MutableStateFlow(SourceNovelUi())
    val ui: StateFlow<SourceNovelUi> = _ui.asStateFlow()

    init { load() }

    fun load() {
        _ui.value = SourceNovelUi(loading = true)
        viewModelScope.launch {
            try {
                val rt = Extensions.runtime(pluginId)
                val novel = rt.novel(path)
                val web = if (rt.info.hasResolveUrl) runCatching { rt.resolveUrl(path, isNovel = true) }.getOrNull()
                          else rt.info.site.trimEnd('/') + "/" + path.trimStart('/')
                _ui.value = SourceNovelUi(
                    loading = false, novel = novel, chapters = novel.chapters,
                    pagesLoaded = if (novel.chapters.isEmpty()) 0 else 1, webUrl = web,
                    imageHeaders = imageHeaders(rt.info.imageRequestInit),
                )
                SourceSession.put(pluginId, path, novel.chapters)
                // Paged chapter lists: fetch the first page up front if none came.
                if (novel.chapters.isEmpty() && (novel.totalPages ?: 0) > 0 && rt.info.hasParsePage) loadMoreChapters()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = SourceNovelUi(loading = false, error = describe(e))
            }
        }
    }

    val canLoadMore: Boolean
        get() = _ui.value.let { s -> (s.novel?.totalPages ?: 0) > s.pagesLoaded }

    fun loadMoreChapters() {
        val s = _ui.value
        if (s.loadingMore || !canLoadMore) return
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val next = s.pagesLoaded + 1
                val page = Extensions.runtime(pluginId).page(path, next)
                _ui.update { it.copy(chapters = it.chapters + page.chapters, pagesLoaded = next, loadingMore = false) }
                SourceSession.put(pluginId, path, _ui.value.chapters)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loadingMore = false, error = describe(e)) }
            }
        }
    }
}

// --- Reading a chapter straight from a source ------------------------------------------

data class SourceReaderUi(
    val index: Int,
    val title: String = "",
    val html: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val count: Int = 0,
)

class SourceReaderViewModel(
    private val pluginId: String,
    private val novelPath: String,
    startIndex: Int,
) : ViewModel() {
    private var chapters: List<ChapterItem> = SourceSession.get(pluginId, novelPath).orEmpty()
    private val _ui = MutableStateFlow(SourceReaderUi(index = startIndex, count = chapters.size))
    val ui: StateFlow<SourceReaderUi> = _ui.asStateFlow()
    private var job: Job? = null

    init { open(startIndex) }

    fun open(index: Int) {
        job?.cancel()
        _ui.update { it.copy(index = index, loading = true, error = null, html = null) }
        job = viewModelScope.launch {
            try {
                val rt = Extensions.runtime(pluginId)
                if (chapters.isEmpty()) {  // opened without the novel page (e.g. after a restart)
                    chapters = rt.novel(novelPath).chapters
                    SourceSession.put(pluginId, novelPath, chapters)
                }
                val ch = chapters.getOrNull(index) ?: error("No chapter ${index + 1}")
                val html = rt.chapter(ch.path)
                _ui.update { it.copy(title = ch.name, html = html, loading = false, count = chapters.size) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = describe(e)) }
            }
        }
    }
}
