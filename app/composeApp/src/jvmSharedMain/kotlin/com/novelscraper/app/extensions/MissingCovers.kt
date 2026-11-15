package com.novelscraper.app.extensions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Covers a source's lists leave out, looked up a novel at a time.
 *
 * Some sites only show a novel's cover on its own page. A source for such a
 * site can offer that one lookup ([PluginInfo.hasCoverOf]); it is asked only
 * for novels on screen, as they come on screen, so scrolling past a hundred
 * titles doesn't load a hundred pages. A source without it is never asked.
 *
 * One lookup at a time per source: a source's calls queue behind one another,
 * and more at once would only hold up the list the reader is paging through.
 * Every answer is kept, a missing cover or a failure too, so scrolling back
 * asks nothing again.
 */
object MissingCovers {
    /** Swapped in tests. */
    internal var lookup: suspend (pluginId: String, path: String) -> String? = { id, path ->
        val rt = Extensions.runtime(id)
        if (rt.info.hasCoverOf) rt.cover(path) else null
    }

    private const val KEPT = 1000

    private val known = object : LinkedHashMap<String, String?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > KEPT
    }
    private val lanes = HashMap<String, Mutex>()

    /** The cover for the novel at [path] on [pluginId], or null for none. */
    suspend fun of(pluginId: String, path: String): String? {
        val key = "$pluginId\u0000$path"
        synchronized(known) { if (known.containsKey(key)) return known[key] }
        val lane = synchronized(lanes) { lanes.getOrPut(pluginId) { Mutex() } }
        return lane.withLock {
            // Asked for meanwhile, by the same novel shown twice.
            synchronized(known) { if (known.containsKey(key)) return known[key] }
            val found = try {
                lookup(pluginId, path)?.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e    // scrolled away: not an answer, so nothing is kept
            } catch (e: Exception) {
                null
            }
            synchronized(known) { known[key] = found }
            found
        }
    }

    internal fun forget() {
        synchronized(known) { known.clear() }
        synchronized(lanes) { lanes.clear() }
    }
}
