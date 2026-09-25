package com.novelscraper.app.library

import com.novelscraper.app.db.openLibraryDriver
import com.novelscraper.app.extensions.PluginException
import com.novelscraper.app.extensions.SiteChallengeException
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.net.Account
import com.novelscraper.app.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The app's library ([LibraryStore]) and its chapter downloader. */
object Library {
    lateinit var store: LibraryStore
        private set

    val ready: Boolean get() = ::store.isInitialized

    /** Open the database; call once at startup, after [Account.init]. */
    fun init(store: LibraryStore = LibraryStore(
        openLibraryDriver(), ExtensionSources, NetServer { Account.signedIn },
        installedSources = { Extensions.installed.value.map { it.id to it.repo } },
    )) {
        this.store = store
        store.onLocalChange = { LibrarySyncRunner.soon() }
        // Installing or removing a source is a change the other devices want.
        Extensions.onInstalledChange = { list ->
            store.scope.launch {
                runCatching { store.noteInstalledSources(list.map { it.id to it.repo }) }
                    .onFailure { Log.w("Library", "note sources: ${it.message}") }
            }
        }
        store.prune()
        ChapterDownloads.start()
    }
}

/**
 * Works through the download queue (download_queue), one chapter at a time,
 * pausing between chapters fetched from a source site so a whole novel is not
 * requested at once. The queue lives in the database, so it survives restarts;
 * a failure pauses it with a message until [resume] (or new downloads) wake it.
 */
object ChapterDownloads {
    data class State(val running: Boolean = false, val error: String? = null)

    private const val SITE_PAUSE_MS = 1_000L
    /** Tries per chapter before it is left for later. */
    private const val TRIES = 3
    /** How many chapters in a row may fail before the queue waits for the reader. */
    private const val GIVE_UP_AFTER = 5
    private const val BACKOFF_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    /** Start (or wake) the worker; it sleeps while the queue is empty, and after
     *  a failure until woken again. */
    @Synchronized
    fun start() {
        wake.trySend(Unit)
        if (worker?.isActive == true) return
        worker = scope.launch {
            val lib = Library.store
            while (true) {
                wake.receive()
                _state.value = State(running = true)
                var inARow = 0        // failures with nothing succeeding in between
                while (true) {
                    val (chapterId, _, attempts) = lib.nextQueued() ?: break
                    try {
                        if (lib.downloadQueued(chapterId)) delay(SITE_PAUSE_MS)
                        inARow = 0
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One chapter failing is not the whole queue failing: try it
                        // again a couple of times, then leave it and move on.
                        inARow++
                        val gaveUp = lib.downloadFailed(chapterId, attempts, TRIES)
                        Log.w("Downloads", "chapter $chapterId (try ${attempts + 1}): ${e.message}")
                        if (inARow >= GIVE_UP_AFTER) {
                            // Everything is failing: the site or the connection is
                            // down, so stop asking until the reader says to.
                            _state.value = State(running = false, error = message(e))
                            break
                        }
                        delay(if (gaveUp) SITE_PAUSE_MS else BACKOFF_MS * (attempts + 1))
                    }
                }
                if (_state.value.error == null) _state.value = State(running = false)
            }
        }
    }

    /** Queue a novel's chapters (all when [positions] is null) and start. */
    fun download(bookId: Int, positions: List<Int>? = null) {
        scope.launch {
            Library.store.enqueueDownloads(bookId, positions)
            start()
        }
    }

    /** Another go at the chapters that gave up. */
    fun retryFailed() {
        scope.launch {
            Library.store.retryFailedDownloads()
            start()
        }
    }

    fun forgetFailed() {
        scope.launch { Library.store.forgetFailedDownloads() }
    }

    fun resume() = start()

    private fun message(e: Exception): String = when (e) {
        is SiteChallengeException -> "Downloads paused: the site asks for a browser check."
        is PluginException -> "Downloads paused: the source failed (${e.message})."
        else -> "Downloads paused: couldn't reach the site."
    }
}

/**
 * When the library syncs with the other devices: a moment after a change here
 * (changes in a burst go together), every minute while the app is in use, when
 * it comes back to the foreground, and once more as it leaves. Only while
 * signed in; a failed sync (offline) just waits for the next one, as nothing
 * unsent is lost.
 */
object LibrarySyncRunner {
    private const val DEBOUNCE_MS = 2_000L
    private const val PERIOD_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** [lastSync]: when the last sync succeeded (ms), 0 if never this run. */
    data class State(
        val syncing: Boolean = false,
        val lastSync: Long = 0,
        val error: Boolean = false,
        /** The server answered, but has no sync in it: it needs updating. */
        val unsupported: Boolean = false,
    )

    /** The app is in use: sync now, then periodically. */
    @Synchronized
    fun start() {
        if (loop?.isActive == true) { wake.trySend(Unit); return }
        loop = scope.launch {
            while (true) {
                runOnce()
                withTimeoutOrNull(PERIOD_MS) { wake.receive() }
                delay(DEBOUNCE_MS)
            }
        }
    }

    /** The app went to the background: one last sync, then stop. */
    @Synchronized
    fun stop() {
        loop?.cancel(); loop = null
        scope.launch { runOnce() }
    }

    /** A change was made here: sync shortly. */
    fun soon() {
        if (loop?.isActive == true) wake.trySend(Unit)
        else scope.launch { delay(DEBOUNCE_MS); runOnce() }
    }

    /** Sync now and wait for it (pull-to-refresh). */
    suspend fun now(): Boolean = runOnce()

    private suspend fun runOnce(): Boolean {
        if (!Account.signedIn) return false
        _state.value = _state.value.copy(syncing = true)
        return try {
            val ok = Library.store.syncNow()
            _state.value = State(syncing = false, lastSync = System.currentTimeMillis(), error = false)
            ok
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = _state.value.copy(syncing = false)
            throw e
        } catch (e: LibraryStore.SyncUnsupportedException) {
            Log.w("Sync", "the server has no sync endpoint (an older build)")
            _state.value = _state.value.copy(syncing = false, error = true, unsupported = true)
            false
        } catch (e: Exception) {
            Log.d("Sync", "sync failed: ${e.message}")
            _state.value = _state.value.copy(syncing = false, error = true, unsupported = false)
            false
        }
    }
}
