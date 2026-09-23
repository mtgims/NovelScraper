package com.novelscraper.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.extensions.InstalledPlugin
import com.novelscraper.app.extensions.NovelItem
import com.novelscraper.app.extensions.PluginException
import com.novelscraper.app.extensions.PluginNotInstalledException
import com.novelscraper.app.extensions.RepoPlugin
import com.novelscraper.app.library.Library
import com.novelscraper.app.extensions.SiteChallengeException
import com.novelscraper.app.extensions.SiteChecks
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val OFFLINE = "Couldn't reach the site. Check your connection; downloaded chapters open without one."

/** What to tell the user when a plugin (or server) call fails. */
fun describe(e: Throwable): String = when (e) {
    is SiteChallengeException ->
        if (SiteChecks.possible) "This site asks for a browser check first: open it from the box that pops up, then try again."
        else "This site asks for a browser check first, which this device can't do."
    is PluginNotInstalledException -> "${e.message} Install it under Browse, Extensions."
    // The plugin host's fetch reports a failed connection this way.
    is PluginException -> if (e.message?.contains("Network request failed") == true) OFFLINE
                          else "The source failed: ${e.message}"
    is java.io.IOException -> OFFLINE
    // Server novels: their chapters come from the server until downloaded.
    is retrofit2.HttpException -> if (e.code() == 401) "Sign in to your server (Settings) to read this."
                                  else "The server answered ${e.code()}."
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

    /** Open a novel from the list: its local id (a row is made the first time). */
    fun open(item: NovelItem, onReady: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                onReady(Library.store.openSource(pluginId, item.path, item.name, item.cover))
            } catch (e: Exception) {
                _ui.update { it.copy(error = describe(e)) }
            }
        }
    }

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
