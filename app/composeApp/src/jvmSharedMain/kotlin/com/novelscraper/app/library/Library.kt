package com.novelscraper.app.library

import com.novelscraper.app.db.openLibraryDriver
import com.novelscraper.app.extensions.PluginException
import com.novelscraper.app.extensions.SiteChallengeException
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

/** The app's library ([LibraryStore]) and its chapter downloader. */
object Library {
    lateinit var store: LibraryStore
        private set

    /** Open the database; call once at startup, after [Account.init]. */
    fun init(store: LibraryStore = LibraryStore(openLibraryDriver(), ExtensionSources, NetServer { Account.signedIn })) {
        this.store = store
        store.prune()
        ChapterDownloads.start()
    }

    /** Import the server's library in the background, if signed in. */
    fun pullServerSoon() {
        if (!Account.signedIn) return
        store.scope.launch {
            runCatching { store.pullServer() }.onFailure { Log.w("Library", "server pull: ${it.message}") }
        }
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
                while (true) {
                    val (chapterId, _, _) = lib.nextQueued() ?: break
                    try {
                        if (lib.downloadQueued(chapterId)) delay(SITE_PAUSE_MS)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Downloads", "chapter $chapterId: ${e.message}")
                        _state.value = State(running = false, error = message(e))
                        break
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

    fun resume() = start()

    private fun message(e: Exception): String = when (e) {
        is SiteChallengeException -> "Downloads paused: the site asks for a browser check."
        is PluginException -> "Downloads paused: the source failed (${e.message})."
        else -> "Downloads paused: couldn't reach the site."
    }
}
