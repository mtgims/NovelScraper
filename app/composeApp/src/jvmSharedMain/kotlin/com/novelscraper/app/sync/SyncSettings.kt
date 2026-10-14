package com.novelscraper.app.sync

import com.novelscraper.app.library.Library
import com.novelscraper.app.library.LibrarySyncRunner
import com.novelscraper.app.net.Net
import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.platform.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Request

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
        GOOGLE_DRIVE("gdrive", "Google Drive"),
        WEBDAV("webdav", "WebDAV"),
    }

    /** Whether this build was given a Google client id (a fork may not be). */
    val googleAvailable: Boolean get() = SyncClients.GOOGLE_CLIENT_ID.isNotEmpty()

    /** [where]: what the folder is, for people (an address, an account). */
    data class Connection(val provider: Provider, val where: String, val encrypted: Boolean)

    private lateinit var prefs: KeyValueStore
    private val _state = MutableStateFlow<Connection?>(null)
    val state: StateFlow<Connection?> = _state.asStateFlow()

    val connected: Boolean get() = _state.value != null

    /** Spares deriving the passphrase's key on every sync. */
    internal val keys = KeyCache()

    /** Google's access token outlives one sync: kept for the next. */
    private var googleSession: OAuthSession? = null

    fun init() {
        prefs = settingsStore("sync")
        _state.value = load()
    }

    private fun load(): Connection? {
        val p = Provider.entries.firstOrNull { it.id == prefs.getString(KEY_PROVIDER, null) } ?: return null
        return when (p) {
            Provider.GOOGLE_DRIVE -> {
                if (prefs.getString(KEY_GOOGLE_REFRESH, null) == null) return null
                Connection(p, prefs.getString(KEY_GOOGLE_EMAIL, null) ?: "Google account", passphrase() != null)
            }
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
        Provider.GOOGLE_DRIVE -> GoogleDriveFolder(googleSession(), client())
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
        Library.store.syncFolder(folder, passphrase.ifEmpty { null }?.toCharArray(), keys)
        prefs.putString(KEY_DAV_URL, url.toString())
        prefs.putString(KEY_DAV_USER, user)
        prefs.putString(KEY_DAV_PASSWORD, password)
        connected(Provider.WEBDAV, passphrase)
    }

    /**
     * Connect to Google Drive: sign in in the browser ([openBrowser]), then
     * prove it with a full sync as [connectWebDav] does. Cancelling stops the
     * wait for the browser.
     */
    suspend fun connectGoogle(passphrase: String, openBrowser: (String) -> Unit) {
        val oauth = GoogleDriveFolder.oauth(client())
        val tokens = oauth.authorize(openBrowser, returnLink = if (isDesktop) null else ANDROID_RETURN)
        val refresh = tokens.refresh ?: throw java.io.IOException("Google gave no lasting sign-in; try again")
        val session = OAuthSession(oauth, refresh, ::saveGoogleRefresh).also { it.seed(tokens) }
        Library.store.syncFolder(GoogleDriveFolder(session, client()), passphrase.ifEmpty { null }?.toCharArray(), keys)
        prefs.putString(KEY_GOOGLE_REFRESH, refresh)
        OAuthClient.idTokenClaim(tokens.idToken, "email")?.let { prefs.putString(KEY_GOOGLE_EMAIL, it) }
            ?: prefs.remove(KEY_GOOGLE_EMAIL)
        googleSession = session
        connected(Provider.GOOGLE_DRIVE, passphrase)
    }

    private fun connected(provider: Provider, passphrase: String) {
        prefs.putString(KEY_PROVIDER, provider.id)
        if (passphrase.isEmpty()) prefs.remove(KEY_PASSPHRASE) else prefs.putString(KEY_PASSPHRASE, passphrase)
        _state.value = load()
        LibrarySyncRunner.start()
    }

    private fun googleSession(): OAuthSession = googleSession ?: OAuthSession(
        GoogleDriveFolder.oauth(client()),
        prefs.getString(KEY_GOOGLE_REFRESH, null).orEmpty(),
        ::saveGoogleRefresh,
    ).also { googleSession = it }

    private fun saveGoogleRefresh(token: String) = prefs.putString(KEY_GOOGLE_REFRESH, token)

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

    private suspend fun forget() {
        // Hand Google's sign-in back rather than leave it valid and unused.
        // Best effort: offline, it simply lapses when unused.
        prefs.getString(KEY_GOOGLE_REFRESH, null)?.let { token ->
            runCatching {
                withContext(Dispatchers.IO) {
                    client().newCall(
                        Request.Builder().url("https://oauth2.googleapis.com/revoke")
                            .post(FormBody.Builder().add("token", token).build()).build()
                    ).execute().close()
                }
            }
        }
        listOf(
            KEY_PROVIDER, KEY_DAV_URL, KEY_DAV_USER, KEY_DAV_PASSWORD, KEY_PASSPHRASE,
            KEY_GOOGLE_REFRESH, KEY_GOOGLE_EMAIL,
        ).forEach(prefs::remove)
        googleSession = null
        _state.value = null
    }

    /** The app's client without its cookies: those belong to the server account. */
    private fun client() = Net.client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

    private const val KEY_PROVIDER = "provider"
    private const val KEY_DAV_URL = "webdav_url"
    private const val KEY_DAV_USER = "webdav_user"
    private const val KEY_DAV_PASSWORD = "webdav_password"
    private const val KEY_PASSPHRASE = "passphrase"
    private const val KEY_GOOGLE_REFRESH = "google_refresh"
    private const val KEY_GOOGLE_EMAIL = "google_email"

    /** Android: the app's launcher entry, from the page that ends a sign-in. */
    private const val ANDROID_RETURN =
        "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;" +
            "package=com.novelscraper.app;end"
}
