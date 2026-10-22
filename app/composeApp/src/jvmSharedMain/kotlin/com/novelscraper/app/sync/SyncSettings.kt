package com.novelscraper.app.sync

import com.novelscraper.app.library.Library
import com.novelscraper.app.library.LibrarySyncRunner
import com.novelscraper.app.net.Net
import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.appFilesDir
import com.novelscraper.app.platform.deviceName
import com.novelscraper.app.platform.hearLocalNetwork
import com.novelscraper.app.platform.settingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Where the library syncs: straight between the devices on the same Wi-Fi,
 * paired by a sync code ([LanSync]), or through a WebDAV folder of the
 * reader's own. Optional; nothing else in the app needs it.
 *
 * The code, or the WebDAV sign-in and passphrase, are kept in this app's
 * private settings, so a sync can run without asking.
 */
object SyncSettings {

    enum class Provider(val id: String, val label: String) {
        LAN("lan", "Same Wi-Fi"),
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

    /** Same-Wi-Fi sync, while it is the connection. */
    private val _lan = MutableStateFlow<LanSync?>(null)
    val lan: StateFlow<LanSync?> = _lan.asStateFlow()
    private var pairKeys: Pair<SyncCode, PairKeys>? = null

    /** The sync folder same-Wi-Fi sync works from, on this device's disk. */
    private val localFolder by lazy { LocalFolder(File(appFilesDir(), "sync-folder")) }

    fun init() {
        prefs = settingsStore("sync")
        _state.value = load()
    }

    private fun load(): Connection? {
        val p = Provider.entries.firstOrNull { it.id == prefs.getString(KEY_PROVIDER, null) } ?: return null
        return when (p) {
            Provider.LAN -> if (code() == null) null else Connection(p, "Devices with this sync code", encrypted = true)
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
        Provider.LAN -> localFolder
        Provider.WEBDAV -> WebDavFolder(
            prefs.getString(KEY_DAV_URL, null).orEmpty(),
            prefs.getString(KEY_DAV_USER, null).orEmpty(),
            prefs.getString(KEY_DAV_PASSWORD, null).orEmpty(),
            client(),
        )
        null -> null
    }

    fun passphrase(): CharArray? = prefs.getString(KEY_PASSPHRASE, null)?.takeIf { it.isNotEmpty() }?.toCharArray()

    /** The sync code's key, which stands in for a passphrase between paired devices. */
    internal fun codeKey() = if (_state.value?.provider == Provider.LAN) code()?.let { keysOf(it).key } else null

    /** This library's sync code, to show so another device can pair. */
    fun code(): SyncCode? = prefs.getString(KEY_CODE, null)?.let { runCatching { SyncCode.parse(it) }.getOrNull() }

    private fun keysOf(code: SyncCode): PairKeys =
        pairKeys?.takeIf { it.first == code }?.second ?: PairKeys(code).also { pairKeys = code to it }

    // --- same Wi-Fi ----------------------------------------------------------------

    /**
     * Sync with the devices on the same Wi-Fi that hold [code]: a new one
     * ([SyncCode.generate]) to start, or another device's to pair with it. The
     * library goes into this device's sync folder at once, and to the others
     * whenever they meet.
     */
    suspend fun connectLan(code: SyncCode) {
        Library.store.syncFolder(localFolder, null, keys, keysOf(code).key)
        prefs.putString(KEY_CODE, code.toString())
        connected(Provider.LAN, passphrase = "")
    }

    /** Start finding and meeting the other devices ([onReceived]: something new arrived). */
    suspend fun startLan(onReceived: () -> Unit) = lanLock.withLock {
        if (_state.value?.provider != Provider.LAN) return@withLock
        val code = code() ?: return@withLock
        val lan = _lan.value ?: LanSync(
            localFolder, keysOf(code), Library.store.syncDevice(), deviceName, onReceived,
            manualPeers = { prefs.getStringSet(KEY_PEERS).orEmpty().toList() },
        ).also { _lan.value = it }
        lan.device = Library.store.syncDevice()
        hearLocalNetwork(true)
        lan.start()
    }

    /** Starting is asked for from more than one place at once (the app coming
     *  to the foreground, sync being set up): one LanSync, however it lands. */
    private val lanLock = Mutex()

    fun stopLan() {
        _lan.value?.stop()
        hearLocalNetwork(false)
    }

    /** After a sync here: hand what changed to the devices around. */
    suspend fun lanExchange() {
        val lan = _lan.value ?: return
        lan.device = Library.store.syncDevice()
        lan.exchangeAll()
    }

    /** A device given by address, for networks that keep devices from hearing
     *  each other's broadcasts. Throws IllegalArgumentException for a bad one. */
    fun addPeer(address: String) {
        requireNotNull(LanSync.splitAddress(address)) { "Write it as the other device shows it, like 192.168.1.20:47812." }
        prefs.putStringSet(KEY_PEERS, prefs.getStringSet(KEY_PEERS).orEmpty() + address.trim())
        LibrarySyncRunner.soon()
    }

    // --- WebDAV --------------------------------------------------------------------

    /**
     * Connect to a WebDAV folder, proving it first with a full sync: a wrong
     * password, a wrong passphrase or an unreachable server throws, and nothing
     * is saved. On success the library is in the folder.
     */
    suspend fun connectWebDav(address: String, user: String, password: String, passphrase: String) {
        val url = WebDavFolder.folderUrl(address)
        require(safeForSecrets(url)) {
            "Use an https address. Plain http is only allowed to a server on this network."
        }
        val folder = WebDavFolder(url.toString(), user, password, client())
        Library.store.syncFolder(folder, passphrase.ifEmpty { null }?.toCharArray(), keys)
        prefs.putString(KEY_DAV_URL, url.toString())
        prefs.putString(KEY_DAV_USER, user)
        prefs.putString(KEY_DAV_PASSWORD, password)
        connected(Provider.WEBDAV, passphrase)
    }

    // --- either ----------------------------------------------------------------------

    private fun connected(provider: Provider, passphrase: String) {
        prefs.putString(KEY_PROVIDER, provider.id)
        if (passphrase.isEmpty()) prefs.remove(KEY_PASSPHRASE) else prefs.putString(KEY_PASSPHRASE, passphrase)
        _state.value = load()
        LibrarySyncRunner.start()
    }

    /**
     * Stop syncing here. The library on this device stays; its sync records go,
     * so connecting again later starts clean.
     */
    suspend fun disconnect() {
        LibrarySyncRunner.cancel()
        forget()
        Library.store.resetSync()
    }

    /**
     * WebDAV: delete every file in the folder, then disconnect. The other
     * devices keep their libraries, and stop syncing with this folder's
     * contents: what they write afterwards starts it again.
     */
    suspend fun deleteSyncedData() {
        val folder = folder() ?: return
        LibrarySyncRunner.cancel()
        folder.list().forEach { folder.delete(it.name) }
        forget()
        Library.store.resetSync()
    }

    private fun forget() {
        stopLan()
        _lan.value = null
        localFolder.clear()
        listOf(KEY_PROVIDER, KEY_CODE, KEY_PEERS, KEY_DAV_URL, KEY_DAV_USER, KEY_DAV_PASSWORD, KEY_PASSPHRASE)
            .forEach(prefs::remove)
        pairKeys = null
        _state.value = null
    }

    /** WebDAV's client: the app's own, which keeps no cookies. */
    private fun client() = Net.client

    private const val KEY_PROVIDER = "provider"
    private const val KEY_CODE = "lan_code"
    private const val KEY_PEERS = "lan_peers"
    private const val KEY_DAV_URL = "webdav_url"
    private const val KEY_DAV_USER = "webdav_user"
    private const val KEY_DAV_PASSWORD = "webdav_password"
    private const val KEY_PASSPHRASE = "passphrase"
}
