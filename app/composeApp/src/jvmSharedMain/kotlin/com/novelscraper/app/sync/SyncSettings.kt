package com.novelscraper.app.sync

import com.novelscraper.app.library.Library
import com.novelscraper.app.library.LibrarySyncRunner
import com.novelscraper.app.net.Net
import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.settingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.CookieJar

/**
 * Where the library syncs: a folder in the user's own storage ([SyncFolder]),
 * and how to reach it. Optional, like the server account it replaces; nothing
 * else in the app needs it.
 *
 * The sign-in and the passphrase are kept in this app's private settings, as
 * the server session was, so a sync can run every minute without asking.
 */
object SyncSettings {

    enum class Provider(val id: String, val label: String) {
        WEBDAV("webdav", "WebDAV"),
    }

    /** [where]: what the folder is, for people (an address, an account). */
    data class Connection(val provider: Provider, val where: String, val encrypted: Boolean)

    private lateinit var prefs: KeyValueStore
    private val _state = MutableStateFlow<Connection?>(null)
    val state: StateFlow<Connection?> = _state.asStateFlow()

    val connected: Boolean get() = _state.value != null

    /** Spares deriving the passphrase's key on every sync. */
    internal val keys = KeyCache()

    fun init() {
        prefs = settingsStore("sync")
        _state.value = load()
    }

    private fun load(): Connection? {
        val p = Provider.entries.firstOrNull { it.id == prefs.getString(KEY_PROVIDER, null) } ?: return null
        return when (p) {
            Provider.WEBDAV -> {
                val url = prefs.getString(KEY_DAV_URL, null) ?: return null
                val user = prefs.getString(KEY_DAV_USER, null).orEmpty()
                Connection(p, "$user @ ${runCatching { WebDavFolder.folderUrl(url).host }.getOrDefault(url)}",
                    passphrase() != null)
            }
        }
    }

    /** The connected folder, or null. */
    fun folder(): SyncFolder? = when (_state.value?.provider) {
        Provider.WEBDAV -> WebDavFolder(
            prefs.getString(KEY_DAV_URL, null).orEmpty(),
            prefs.getString(KEY_DAV_USER, null).orEmpty(),
            prefs.getString(KEY_DAV_PASSWORD, null).orEmpty(),
            client(),
        )
        null -> null
    }

    fun passphrase(): CharArray? = prefs.getString(KEY_PASSPHRASE, null)?.takeIf { it.isNotEmpty() }?.toCharArray()

    /**
     * Connect to a WebDAV folder, proving it first with a full sync: a wrong
     * password, a wrong passphrase or an unreachable server throws, and nothing
     * is saved. On success the library is in the folder.
     */
    suspend fun connectWebDav(address: String, user: String, password: String, passphrase: String) {
        val url = WebDavFolder.folderUrl(address)
        require(WebDavFolder.safeToSignIn(url)) {
            "Use an https address. Plain http is only allowed to a server on this network."
        }
        val folder = WebDavFolder(url.toString(), user, password, client())
        val secret = passphrase.ifEmpty { null }?.toCharArray()
        Library.store.syncFolder(folder, secret, keys)
        prefs.putString(KEY_PROVIDER, Provider.WEBDAV.id)
        prefs.putString(KEY_DAV_URL, url.toString())
        prefs.putString(KEY_DAV_USER, user)
        prefs.putString(KEY_DAV_PASSWORD, password)
        if (passphrase.isEmpty()) prefs.remove(KEY_PASSPHRASE) else prefs.putString(KEY_PASSPHRASE, passphrase)
        _state.value = load()
        LibrarySyncRunner.start()
    }

    /**
     * Stop syncing here. The library on this device stays; its sync records go,
     * so connecting to another folder later starts clean.
     */
    suspend fun disconnect() {
        LibrarySyncRunner.cancel()
        forget()
        Library.store.resetSync()
    }

    /**
     * Delete every file in the folder, then disconnect. The other devices keep
     * their libraries, and stop syncing with this folder's contents: what they
     * write afterwards starts it again.
     */
    suspend fun deleteSyncedData() {
        val folder = folder() ?: return
        LibrarySyncRunner.cancel()
        folder.list().forEach { folder.delete(it.name) }
        forget()
        Library.store.resetSync()
    }

    private fun forget() {
        listOf(KEY_PROVIDER, KEY_DAV_URL, KEY_DAV_USER, KEY_DAV_PASSWORD, KEY_PASSPHRASE).forEach(prefs::remove)
        _state.value = null
    }

    /** The app's client without its cookies: those belong to the server account. */
    private fun client() = Net.client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

    private const val KEY_PROVIDER = "provider"
    private const val KEY_DAV_URL = "webdav_url"
    private const val KEY_DAV_USER = "webdav_user"
    private const val KEY_DAV_PASSWORD = "webdav_password"
    private const val KEY_PASSPHRASE = "passphrase"
}
