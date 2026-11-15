package com.novelscraper.app.library

import com.novelscraper.app.extensions.NovelItem
import com.novelscraper.app.extensions.SiteBlockedException
import com.novelscraper.app.extensions.SiteChallengeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Looks for a novel on other sources, in the order given: the first source
 * with the very title wins and the rest aren't asked. A near title is kept
 * while the rest are asked for the exact one.
 */
class MatchFinder(private val search: suspend (pluginId: String, title: String) -> List<NovelItem>) {

    data class Found(val pluginId: String, val item: NovelItem, val exact: Boolean)

    /**
     * A source that couldn't be asked: behind a browser check, blocking, or
     * failing. [lasting]: a check or a block, which won't pass by itself, so the
     * source isn't asked again in the same run; anything else may be one slow
     * answer.
     */
    data class Unavailable(val pluginId: String, val url: String?, val check: Boolean, val error: Exception) {
        val lasting: Boolean get() = check || error is SiteBlockedException
    }

    data class Result(val found: Found?, val unavailable: List<Unavailable>)

    suspend fun find(title: String, targets: List<String>, skip: Set<String> = emptySet()): Result {
        var near: Found? = null
        val unavailable = mutableListOf<Unavailable>()
        for (id in targets) {
            if (id in skip) continue
            val items = try {
                search(id, title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SiteChallengeException) {
                unavailable += Unavailable(id, e.url, check = true, error = e); continue
            } catch (e: SiteBlockedException) {
                unavailable += Unavailable(id, e.url, check = false, error = e); continue
            } catch (e: Exception) {
                unavailable += Unavailable(id, null, check = false, error = e); continue
            }
            val exact = items.firstOrNull { TitleMatch.of(title, it.name) == TitleMatch.Kind.Exact }
            if (exact != null) return Result(Found(id, exact, true), unavailable)
            if (near == null) {
                items.firstOrNull { TitleMatch.of(title, it.name) == TitleMatch.Kind.Near }
                    ?.let { near = Found(id, it, false) }
            }
        }
        return Result(near, unavailable)
    }
}

/**
 * Moving the novels of one source to others: each is looked for, its match's
 * chapters fetched and what would move worked out, before anything moves.
 */
class SourceMigration(
    private val store: LibraryStore,
    private val finder: MatchFinder,
    private val installed: (pluginId: String) -> Boolean,
    private val describe: (Exception) -> String,
    /** Pause before a novel's searches, so a run doesn't hammer the sites. */
    private val gapMs: Long = 2000,
    /** Runs a fetch from a source, in that source's turn (the searches' pacing). */
    private val paced: suspend (pluginId: String, fetch: suspend () -> Unit) -> Unit = { _, fetch -> fetch() },
) {
    sealed interface Prepared {
        data class Ready(
            val fromId: Int,
            val toId: Int,
            val pluginId: String,
            val item: NovelItem,
            val preview: MigrationPreview,
            /** Moved unless the reader unticks it. */
            val tick: Boolean,
            /** Why it isn't ticked, or what to check. */
            val note: String?,
            /** Ticked anyway, but worth a look after moving. */
            val hint: String? = null,
        ) : Prepared
        data object NoMatch : Prepared
        data class Failed(val pluginId: String?, val reason: String) : Prepared
    }

    data class Outcome(val prepared: Prepared, val unavailable: List<MatchFinder.Unavailable>)

    suspend fun prepare(book: LibBook, targets: List<String>, skip: Set<String> = emptySet()): Outcome {
        if (gapMs > 0) delay(gapMs)
        // Arrived by sync and never opened: its chapters are needed to match what was read.
        if (book.chapterCount == 0 && book.pluginId != null && installed(book.pluginId)) {
            try { store.refresh(book.id) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
        val order = targets.filter { it != book.pluginId }
        val result = finder.find(book.title, order, skip)
        val found = result.found ?: return Outcome(Prepared.NoMatch, result.unavailable)
        // A source asked first failed this once: the match is from one further down.
        val missed = result.unavailable.filter { !it.lasting && order.indexOf(it.pluginId) < order.indexOf(found.pluginId) }
        val prepared = ready(book, found.pluginId, found.item, found.exact, missed.map { it.pluginId })
        return Outcome(prepared, result.unavailable)
    }

    /** A match picked by hand, or found: fetch it and say what would move. */
    suspend fun ready(
        book: LibBook,
        pluginId: String,
        item: NovelItem,
        exact: Boolean = true,
        /** Sources ahead of this one in the order that failed to answer. */
        missed: List<String> = emptyList(),
    ): Prepared = try {
        val toId = store.openSource(pluginId, item.path, item.name, item.cover)
        if (toId == book.id) error("That is the novel being moved.")
        paced(pluginId) { store.refresh(toId) }
        val p = store.migrationPreview(book.id, toId)
        val note = when {
            p.chaptersHere == 0 -> "Its chapters were never loaded here, so what was read can't be matched."
            !exact -> "The title isn't quite the same: check it is the same novel."
            // Moving onto it would add these read marks to its own and take its place.
            store.book(toId)?.inLibrary == true -> "It is already in the library from this source."
            p.pastTheEnd -> "This source doesn't go as far as it was read."
            else -> null
        }
        val hint = when {
            missed.isNotEmpty() -> "Taken from further down the list: an earlier source didn't answer."
            !p.byNumber && p.readHere > 0 -> "Chapters don't number the same way here, so they were matched by their order."
            p.resumeHere != null && !p.exact -> "No chapter like the one being read; the nearest was taken."
            else -> null
        }
        Prepared.Ready(book.id, toId, pluginId, item, p, tick = note == null, note = note, hint = hint)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Prepared.Failed(pluginId, describe(e))
    }

    suspend fun move(fromId: Int, toId: Int, keepOld: Boolean, downloadAgain: Boolean) =
        store.migrate(fromId, toId, keepOld, downloadAgain)
}
