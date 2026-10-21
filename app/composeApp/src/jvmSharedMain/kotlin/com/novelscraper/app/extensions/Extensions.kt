package com.novelscraper.app.extensions

import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.appFilesDir
import com.novelscraper.app.platform.browserUserAgent
import com.novelscraper.app.platform.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** A plugin as a repository index lists it (LNReader's plugins.min.json format). */
@Serializable
data class RepoPlugin(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val url: String,
    val iconUrl: String = "",
    /** The repository it came from (filled in when listed). */
    val repo: String = "",
)

@Serializable
data class InstalledPlugin(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val iconUrl: String = "",
    val repo: String = "",
    /** Headers its images (covers) need, from the plugin's imageRequestInit. */
    val imageHeaders: Map<String, String> = emptyMap(),
)

/**
 * Source extensions: which repositories to list plugins from, which plugins are
 * installed (their code under <files>/extensions/), and a small cache of running
 * plugins ([runtime]).
 *
 * Repositories are plugin indexes in LNReader's format (a JSON array of
 * [RepoPlugin]), added by the user by URL: NovelScraper's own
 * (github.com/mtgims/novelscraper-extensions), LNReader's, or anyone's. The app
 * ships with none and installs nothing by itself.
 */
object Extensions {

    private const val TAG = "Extensions"
    private const val MAX_RUNNING = 4
    /** The largest repository index or plugin code accepted (both are kilobytes). */
    private const val MAX_REPO_BYTES = 8L * 1024 * 1024

    /** Where evicted runtimes are closed, once they go idle. */
    private val evicting = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private lateinit var prefs: KeyValueStore
    private lateinit var dataPrefs: KeyValueStore
    private lateinit var dir: File

    /** Downloads plugin code and indexes, and is what plugins fetch through: its
     *  own cookie jar, like a browser's, separate from the server login. */
    lateinit var http: OkHttpClient

    /** [http], never changing scheme on a redirect: for repositories and plugin code ([get]). */
    private lateinit var repoHttp: OkHttpClient
        private set

    /** The cookies sources set while plugins browse them (and what a browser
     *  check leaves behind). */
    lateinit var cookies: BrowserCookieJar
        private set

    private val _repos = MutableStateFlow<List<String>>(emptyList())
    val repos: StateFlow<List<String>> = _repos.asStateFlow()

    private val _installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed: StateFlow<List<InstalledPlugin>> = _installed.asStateFlow()

    private val mutex = Mutex()
    private val running = LinkedHashMap<String, PluginRuntime>(8, 0.75f, true)
    private val storageCache = HashMap<String, MutableMap<String, String>>()

    fun init(base: OkHttpClient = OkHttpClient()) {
        prefs = settingsStore("extensions")
        dataPrefs = settingsStore("plugin-data")
        dir = File(appFilesDir(), "extensions")
        cookies = BrowserCookieJar(settingsStore("site-cookies"))
        http = base.newBuilder()
            .cookieJar(cookies)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
        repoHttp = http.newBuilder().followSslRedirects(false).build()
        _repos.value = prefs.getString("repos", null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?: emptyList()
        _installed.value = runCatching {
            json.decodeFromString(ListSerializer(InstalledPlugin.serializer()), File(dir, "installed.json").readText())
        }.onFailure {
            // Only absent on a first run. Anything else means the list was
            // damaged, and carrying on as though no source was ever installed
            // is a drastic thing to do in silence.
            if (File(dir, "installed.json").exists()) {
                Log.w(TAG, "installed.json could not be read, starting with no sources: ${it.message}")
            }
        }.getOrDefault(emptyList())
    }

    // --- repositories --------------------------------------------------------------

    fun addRepo(url: String) {
        val u = url.trim()
        if (u.isEmpty() || u in _repos.value) return
        setRepos(_repos.value + u)
    }

    fun removeRepo(url: String) = setRepos(_repos.value - url)

    private fun setRepos(list: List<String>) {
        _repos.value = list
        prefs.putString("repos", json.encodeToString(ListSerializer(String.serializer()), list))
    }

    /** Every plugin the repositories offer. A repository that
     *  can't be reached is skipped and reported in [AvailableResult.failed]. */
    suspend fun available(): AvailableResult = withContext(Dispatchers.IO) {
        // Asked all at once. One repository at a time meant opening Browse
        // cost the sum of every repository's response, and one slow or dead
        // host held up all the others behind it.
        val fetched = coroutineScope {
            _repos.value.map { repo ->
                async {
                    repo to runCatching {
                        json.decodeFromString(ListSerializer(RepoPlugin.serializer()), get(repo))
                            .map { it.copy(repo = repo) }
                    }
                }
            }.awaitAll()
        }
        val plugins = ArrayList<RepoPlugin>()
        val failed = ArrayList<String>()
        for ((repo, result) in fetched) {
            result.onSuccess { plugins += it }
                .onFailure { Log.w(TAG, "repo $repo: ${it.message}"); failed += repo }
        }
        // Distinct per repository, not per id. Collapsing by id alone dropped
        // whichever repository came second, so a source could be hidden by an
        // unrelated one that happened to use the same id, and the offer from
        // the repository a plugin was actually installed from could be the one
        // thrown away, leaving no update where there was one.
        AvailableResult(plugins.distinctBy { it.id to it.repo }, failed)
    }

    data class AvailableResult(val plugins: List<RepoPlugin>, val failed: List<String>)

    // --- install / update / remove --------------------------------------------------

    /** Download the plugin's code, check that it loads,
     *  and add it to [installed]; replaces an installed older version. */
    suspend fun install(p: RepoPlugin) {
        // Downloaded and checked outside the lock. runtime() needs the same
        // lock to start a source, so holding it across a download meant every
        // other source stopped working for as long as the network took:
        // installing one thing froze browsing everything.
        val code = withContext(Dispatchers.IO) { get(p.url) }
        // Refuse code that doesn't load, before it replaces a working version.
        val headers = PluginRuntime.load(p.id, code, environment()).use { rt ->
            require(rt.info.id == p.id) { "The plugin calls itself ${rt.info.id}, the index says ${p.id}" }
            headersOf(rt.info)
        }
        // The lock covers only what it has to: putting the file in place and
        // recording it.
        mutex.withLock {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val f = codeFile(p.id)
                val tmp = File(dir, "${f.name}.tmp")
                tmp.writeText(code)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
            // Idle first, for the same reason eviction waits: the version being
            // replaced may be mid-call, and ending its interpreter there would
            // cut that call off.
            running.remove(p.id)?.let { old ->
                evicting.launch { runCatching { old.closeWhenIdle() } }
            }
            val entry = InstalledPlugin(p.id, p.name, p.site, p.lang, p.version, p.iconUrl, p.repo, headers)
            saveInstalled(_installed.value.filter { it.id != p.id } + entry)
        }
    }

    suspend fun uninstall(id: String) = mutex.withLock {
        // Closed once idle, as install and eviction do: a call under way (a
        // download, a browse) finishes rather than being cut off.
        running.remove(id)?.let { old -> evicting.launch { runCatching { old.closeWhenIdle() } } }
        withContext(Dispatchers.IO) { codeFile(id).delete() }
        storageCache.remove(id)
        dataPrefs.remove(id)
        saveInstalled(_installed.value.filter { it.id != id })
    }

    /**
     * The update on offer for [installed], or null if there is none.
     *
     * Matched on id **and** repository. Matching on id alone meant any
     * repository added afterwards could list an installed plugin's id with a
     * higher version and have it quietly replace the real one, which is a
     * plugin swap rather than an update: the code runs on the reader's device
     * with that source's stored session already attached to it.
     */
    fun updateFor(installed: InstalledPlugin, available: List<RepoPlugin>): RepoPlugin? {
        val sameId = available.filter { it.id == installed.id }
        val candidate = when {
            installed.repo.isNotBlank() -> sameId.firstOrNull { it.repo == installed.repo }
            // Installed before the repository was recorded. One offer is
            // unambiguous; several mean there is no way to tell which one it
            // came from, and guessing is the hole being closed.
            sameId.size == 1 -> sameId.single()
            else -> null
        }
        return candidate?.takeIf { compareVersions(it.version, installed.version) > 0 }
    }

    /** Called after a source is installed or removed, so the library can sync
     *  which sources this device has. Set by Library.init. */
    var onInstalledChange: ((List<InstalledPlugin>) -> Unit)? = null

    private fun saveInstalled(list: List<InstalledPlugin>) {
        _installed.value = list.sortedBy { it.name.lowercase() }
        dir.mkdirs()
        // Written beside and renamed, the way the plugin code files already
        // are. writeText truncates first, so losing power or crashing in the
        // middle left a half-written list, and the read below treats anything
        // it cannot parse as "no sources installed": every novel in the
        // library unopenable, with nothing to say why. A rename cannot leave
        // that state.
        val target = File(dir, "installed.json")
        val tmp = File(dir, "installed.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(InstalledPlugin.serializer()), _installed.value))
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) Log.w(TAG, "could not replace installed.json")
        }
        onInstalledChange?.invoke(_installed.value)
    }

    private fun codeFile(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".js")

    // --- running plugins ------------------------------------------------------------

    /** The installed plugin, running (loaded on first use, a few kept warm). */
    suspend fun runtime(id: String): PluginRuntime {
        mutex.withLock { running[id]?.let { return it } }
        // Loaded outside the lock. Loading runs the plugin's own code, and every
        // source takes this lock to start, so one slow or looping plugin held
        // under it stopped all the others.
        while (true) {
            val code = currentCode(id) ?: throw notInstalled(id)
            val fresh = PluginRuntime.load(id, code, environment())
            val registered = mutex.withLock {
                running[id]?.let { existing ->
                    // Another caller started it meanwhile: use theirs. This one
                    // was never called, so closing it is safe.
                    fresh.close()
                    return existing
                }
                when (currentCode(id)) {
                    null -> { fresh.close(); throw notInstalled(id) }   // uninstalled meanwhile
                    code -> register(id, fresh)
                    else -> { fresh.close(); null }                     // updated meanwhile: load the new one
                }
            }
            if (registered != null) return registered
        }
    }

    private suspend fun currentCode(id: String): String? = withContext(Dispatchers.IO) {
        codeFile(id).takeIf { it.exists() }?.readText()
    }

    /** Usually a novel synced from another device, whose source this one hasn't installed. */
    private fun notInstalled(id: String) =
        PluginNotInstalledException(_installed.value.firstOrNull { it.id == id }?.name ?: id)

    /** Put a loaded plugin in use (under [mutex]), making room by evicting the least used. */
    private fun register(id: String, rt: PluginRuntime): PluginRuntime {
        running[id] = rt
        // Plugins installed before image headers were recorded get them now.
        val headers = headersOf(rt.info)
        _installed.value.firstOrNull { it.id == id }?.takeIf { it.imageHeaders != headers }?.let { entry ->
            saveInstalled(_installed.value.map { if (it.id == id) entry.copy(imageHeaders = headers) else it })
        }
        while (running.size > MAX_RUNNING) {
            val eldest = running.entries.first()
            running.remove(eldest.key)
            val evicted = eldest.value
            // Dropped from the map at once, so nothing new reaches it, but
            // closed only once it is idle. close() ends the interpreter where
            // it stands, and with more than MAX_RUNNING sources in use the one
            // falling off the end can be in the middle of a call: closing a
            // QuickJS context under a running evaluation cuts that call off
            // ("Already closed", measured 2026-10-21), or worse.
            //
            // Off this coroutine as well, because waiting here would hold the
            // extensions lock for as long as that call takes, which is up to
            // the full plugin timeout, and block every other source meanwhile.
            evicting.launch { runCatching { evicted.closeWhenIdle() } }
        }
        return rt
    }

    /** Headers a source's images need (Referer, User-Agent); empty for most. */
    fun imageHeaders(pluginId: String?): Map<String, String> =
        pluginId?.let { id -> _installed.value.firstOrNull { it.id == id }?.imageHeaders }.orEmpty()

    private fun headersOf(info: PluginInfo): Map<String, String> =
        (info.imageRequestInit?.get("headers") as? kotlinx.serialization.json.JsonObject)
            ?.mapValues { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
            .orEmpty()

    private fun environment() = PluginEnvironment(http, browserUserAgent, ::storageFor, cookies = cookies)

    /** A plugin's storage: a map persisted as JSON under its id. */
    private fun storageFor(id: String): MutableMap<String, String> = synchronized(storageCache) {
        storageCache.getOrPut(id) {
            val initial = dataPrefs.getString(id, null)
                ?.let { runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it) }.getOrNull() }
                .orEmpty()
            PersistentMap(initial) { m ->
                dataPrefs.putString(id, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), m))
            }
        }
    }

    /** A map that saves itself on every change (plugins write rarely). */
    private class PersistentMap(
        initial: Map<String, String>,
        private val save: (Map<String, String>) -> Unit,
    ) : AbstractMutableMap<String, String>() {
        private val m = HashMap(initial)
        override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
            get() = synchronized(m) { HashMap(m).entries }
        override val keys: MutableSet<String> get() = synchronized(m) { m.keys.toMutableSet() }
        override fun get(key: String): String? = synchronized(m) { m[key] }
        override fun put(key: String, value: String): String? =
            synchronized(m) { m.put(key, value).also { save(HashMap(m)) } }
        override fun remove(key: String): String? =
            synchronized(m) { m.remove(key).also { save(HashMap(m)) } }
    }

    /**
     * Refuse to fetch a repository index or plugin code over plain http.
     *
     * The app allows cleartext generally, and has to: sources read novel sites
     * the reader chose, and plenty of those are still http. Reading a page that
     * way risks the page. This is different in kind. A repository index decides
     * which code is offered, and plugin code *runs on the device*, with that
     * source's stored tokens and cookies available to it. Over http, anyone on
     * the path between here and the server chooses what runs, which is not a
     * weaker guarantee but no guarantee at all.
     *
     * Loopback is allowed, because a repository served from the reader's own
     * machine has no network path for anyone to sit on, and writing a source is
     * hard enough without forbidding a local index.
     */
    internal fun requireTrustworthyOrigin(url: String) {
        val parsed = url.toHttpUrlOrNull() ?: error("not a usable address: $url")
        if (parsed.isHttps) return
        val host = parsed.host
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return
        error("$host has to be served over https. Repository indexes and plugin " +
              "code are run on this device, and over plain http whoever is " +
              "between here and that server decides what runs.")
    }

    /**
     * A repository index or a plugin's code. Checking only the address asked
     * for was not enough: the client followed a redirect from https to plain
     * http, so a repository could hand its code over in the clear after all.
     * This client never changes scheme on a redirect. Read with a ceiling,
     * since the whole body is held in memory.
     */
    private fun get(url: String): String {
        requireTrustworthyOrigin(url)
        val request = Request.Builder().url(url).header("User-Agent", browserUserAgent).build()
        return repoHttp.newCall(request).execute().use { r ->
            if (r.isRedirect) error("$url redirects to ${r.header("Location")}, which this won't follow: not over https")
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val source = r.body?.source() ?: error("empty response")
            source.request(MAX_REPO_BYTES + 1)
            if (source.buffer.size > MAX_REPO_BYTES) error("$url is over ${MAX_REPO_BYTES / (1024 * 1024)} MiB")
            source.buffer.readUtf8()
        }
    }
}

/** Compare dotted version strings numerically ("2.10.0" > "2.9.1"). */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}
