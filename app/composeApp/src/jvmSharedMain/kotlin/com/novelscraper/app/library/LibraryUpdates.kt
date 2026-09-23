package com.novelscraper.app.library

import com.novelscraper.app.extensions.PluginNotInstalledException
import com.novelscraper.app.platform.KeyValueStore
import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.settingsStore
import com.novelscraper.app.platform.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Looks through the library for chapters that have appeared since last time.
 *
 * One novel at a time with a pause between them: a library of eighty novels must
 * not arrive at a source as a burst of eighty requests. Novels whose source
 * isn't installed here are skipped, and a source that fails (offline, a browser
 * check) is counted and passed over rather than stopping the run. Novels stored
 * on the server are left alone: the server updates those itself.
 *
 * It runs when the library is opened, at most every [MIN_HOURS] hours, and
 * whenever the reader asks for it.
 */
object LibraryUpdates {

    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        /** The novel being checked. */
        val novel: String = "",
        val newChapters: Int = 0,
        val failed: Int = 0,
    )

    private const val MIN_HOURS = 6
    private const val PAUSE_MS = 1_500L
    private const val KEY_LAST = "updates_last_run"
    private const val TAG = "LibraryUpdates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private val prefs: KeyValueStore by lazy { settingsStore("library") }

    /** When the last full check finished (ms), 0 if never. */
    val lastRun: Long get() = prefs.getString(KEY_LAST, null)?.toLongOrNull() ?: 0

    /**
     * Check every source novel in the library. Unless [force], this does nothing
     * if the last check was recent. [announce] shows the result as a toast.
     */
    fun checkAll(force: Boolean = false, announce: Boolean = force) {
        if (job?.isActive == true) return
        val since = System.currentTimeMillis() - lastRun
        if (!force && since < MIN_HOURS * 3600_000L) return
        job = scope.launch {
            val novels = Library.store.libraryFlowOnce().filter { it.isSource }
            if (novels.isEmpty()) return@launch
            _state.value = State(running = true, total = novels.size)
            var new = 0
            var failed = 0
            for ((i, novel) in novels.withIndex()) {
                if (!coroutineContext.isActive) break
                _state.value = _state.value.copy(done = i, novel = novel.title)
                val before = novel.chapterCount
                try {
                    Library.store.refresh(novel.id)
                    val after = Library.store.progress(novel.id).total
                    if (after > before) new += after - before
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: PluginNotInstalledException) {
                    // Synced from another device; nothing to check here.
                } catch (e: Exception) {
                    failed++
                    Log.w(TAG, "${novel.title}: ${e.message}")
                }
                _state.value = _state.value.copy(newChapters = new, failed = failed)
                if (i < novels.lastIndex) delay(PAUSE_MS)
            }
            prefs.putString(KEY_LAST, System.currentTimeMillis().toString())
            _state.value = _state.value.copy(running = false, done = novels.size, novel = "")
            if (announce) showToast(message(new, failed), long = true)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false)
    }

    internal fun message(new: Int, failed: Int): String {
        val found = when (new) {
            0 -> "No new chapters"
            1 -> "1 new chapter"
            else -> "$new new chapters"
        }
        val missed = when (failed) {
            0 -> ""
            1 -> ", 1 source couldn't be reached"
            else -> ", $failed sources couldn't be reached"
        }
        return found + missed + "."
    }
}
