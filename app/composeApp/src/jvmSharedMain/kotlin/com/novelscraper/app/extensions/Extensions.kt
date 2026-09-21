package com.novelscraper.app.extensions

import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.appFilesDir
import com.novelscraper.app.platform.browserUserAgent
import com.novelscraper.app.platform.settingsStore
import kotlinx.coroutines.Dispatchers
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
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private lateinit var prefs: KeyValueStore
    private lateinit var dataPrefs: KeyValueStore
    private lateinit var dir: File

    /** Downloads plugin code and indexes, and is what plugins fetch through: its
     *  own cookie jar, like a browser's, separate from the server login. */
    lateinit var http: OkHttpClient
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
        http = base.newBuilder()
            .cookieJar(BrowserCookieJar())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
        _repos.value = prefs.getString("repos", null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?: emptyList()
        _installed.value = runCatching {
            json.decodeFromString(ListSerializer(InstalledPlugin.serializer()), File(dir, "installed.json").readText())
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
        val plugins = ArrayList<RepoPlugin>()
        val failed = ArrayList<String>()
        for (repo in _repos.value) {
            try {
                val body = get(repo)
                plugins += json.decodeFromString(ListSerializer(RepoPlugin.serializer()), body).map { it.copy(repo = repo) }
            } catch (e: Exception) {
                Log.w(TAG, "repo $repo: ${e.message}")
                failed += repo
            }
        }
        AvailableResult(plugins.distinctBy { it.id }, failed)
    }

    data class AvailableResult(val plugins: List<RepoPlugin>, val failed: List<String>)

    // --- install / update / remove --------------------------------------------------

    /** Download the plugin's code, check that it loads,
     *  and add it to [installed]; replaces an installed older version. */
    suspend fun install(p: RepoPlugin) = mutex.withLock {
        val code = withContext(Dispatchers.IO) { get(p.url) }
        // Refuse code that doesn't load, before it replaces a working version.
        PluginRuntime.load(p.id, code, environment()).use { rt ->
            require(rt.info.id == p.id) { "The plugin calls itself ${rt.info.id}, the index says ${p.id}" }
        }
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val f = codeFile(p.id)
            val tmp = File(dir, "${f.name}.tmp")
            tmp.writeText(code)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        running.remove(p.id)?.close()
        val entry = InstalledPlugin(p.id, p.name, p.site, p.lang, p.version, p.iconUrl, p.repo)
        saveInstalled(_installed.value.filter { it.id != p.id } + entry)
    }

    suspend fun uninstall(id: String) = mutex.withLock {
        running.remove(id)?.close()
        withContext(Dispatchers.IO) { codeFile(id).delete() }
        storageCache.remove(id)
        dataPrefs.remove(id)
        saveInstalled(_installed.value.filter { it.id != id })
    }

    /** True if [available] offers a newer version than the installed one. */
    fun hasUpdate(installed: InstalledPlugin, offered: RepoPlugin?): Boolean =
        offered != null && compareVersions(offered.version, installed.version) > 0

    private fun saveInstalled(list: List<InstalledPlugin>) {
        _installed.value = list.sortedBy { it.name.lowercase() }
        dir.mkdirs()
        File(dir, "installed.json").writeText(json.encodeToString(ListSerializer(InstalledPlugin.serializer()), _installed.value))
    }

    private fun codeFile(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".js")

    // --- running plugins ------------------------------------------------------------

    /** The installed plugin, running (loaded on first use, a few kept warm). */
    suspend fun runtime(id: String): PluginRuntime = mutex.withLock {
        running[id]?.let { return@withLock it }
        val code = withContext(Dispatchers.IO) { codeFile(id).readText() }
        val rt = PluginRuntime.load(id, code, environment())
        running[id] = rt
        while (running.size > MAX_RUNNING) {
            val eldest = running.entries.first()
            running.remove(eldest.key)
            eldest.value.close()
        }
        rt
    }

    private fun environment() = PluginEnvironment(http, browserUserAgent, ::storageFor)

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

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", browserUserAgent).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body?.string() ?: error("empty response")
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
