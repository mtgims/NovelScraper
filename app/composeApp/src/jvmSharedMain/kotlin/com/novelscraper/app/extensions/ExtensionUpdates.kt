package com.novelscraper.app.extensions

import com.novelscraper.app.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Newer versions of installed sources, so the app can say so without anyone
 * opening Extensions to find out.
 *
 * The repositories are asked when the app starts, and again when the reader
 * opens Extensions, which asks them anyway. What is waiting is worked out with
 * [Extensions.updateFor], the same rule the Extensions screen uses, so the two
 * never disagree; it follows installs as they happen, so updating a source
 * takes it off the count at once.
 */
object ExtensionUpdates {
    private const val TAG = "ExtensionUpdates"
    private const val AGAIN_AFTER_MS = 6 * 3600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val catalogue = MutableStateFlow<List<RepoPlugin>>(emptyList())
    @Volatile private var checkedAt = 0L

    /** The update on offer for each installed source that has one. */
    val offers: StateFlow<List<RepoPlugin>> =
        combine(Extensions.installed, catalogue) { installed, offered -> pending(installed, offered) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** What [installed] can update to from [offered]. */
    internal fun pending(installed: List<InstalledPlugin>, offered: List<RepoPlugin>): List<RepoPlugin> =
        installed.mapNotNull { Extensions.updateFor(it, offered) }

    /**
     * The repositories answered with [plugins]; [failed] didn't answer. What
     * was known from those stays: a repository down for a moment doesn't mean
     * its updates went away.
     */
    fun noteCatalogue(plugins: List<RepoPlugin>, failed: List<String>) {
        catalogue.value = plugins + catalogue.value.filter { it.repo in failed }
        checkedAt = System.currentTimeMillis()
    }

    /** Ask the repositories, unless that was done in the last few hours. */
    fun checkQuietly() {
        if (System.currentTimeMillis() - checkedAt < AGAIN_AFTER_MS) return
        checkedAt = System.currentTimeMillis()
        scope.launch {
            try {
                val r = Extensions.available()
                noteCatalogue(r.plugins, r.failed)
            } catch (e: Exception) {
                Log.w(TAG, "couldn't ask the repositories: ${e.message}")
            }
        }
    }

    internal fun forget() {
        catalogue.value = emptyList()
        checkedAt = 0L
    }
}
