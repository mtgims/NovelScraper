package com.novelscraper.app.library

import com.novelscraper.app.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** One novel in a reading-progress backup: what it was called and how far it was read. */
@Serializable
data class BackupNovel(
    val title: String,
    val read: Int,
    val total: Int = 0,
    val author: String = "",
    /** The source it was read on, when the backup says (the server's exports did). */
    val site: String = "",
    /** The chapter it was open at, when the backup says. The server said 1 for
     *  a novel never opened, so 1 tells nothing ([StatsRestore] reads on from [read]). */
    val position: Int? = null,
)

/**
 * Reads a reading-progress backup: the statistics page's export, or the one
 * the sync server gave before it was switched off (2026-10-21). Four shapes:
 *
 * - the app's JSON: an object whose `books` hold `title`, `total_chapters`, `read_count`;
 * - the app's CSV: `title,total_chapters,read_chapters,percent_read`;
 * - the server's JSON: a list of rows with `title`, `author`, `site`,
 *   `current_position`, `chapters_read`, `total_chapters`;
 * - the server's CSV: the same, under headings ("Chapters read", "Current chapter #").
 *
 * None of them says where a novel can be fetched from, which is why a restore
 * goes by title.
 */
object ProgressBackup {

    class Unreadable(message: String) : Exception(message)

    fun parse(text: String): List<BackupNovel> {
        val body = text.removePrefix("\uFEFF").trim()
        if (body.isEmpty()) throw Unreadable("The file is empty.")
        val novels = if (body.startsWith("{") || body.startsWith("[")) fromJson(body) else fromCsv(body)
        return novels.filter { it.title.isNotBlank() }
    }

    private fun fromJson(body: String): List<BackupNovel> {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: throw Unreadable("The file isn't readable JSON.")
        val rows = when (root) {
            is JsonArray -> root
            is JsonObject -> root["books"] as? JsonArray
            else -> null
        } ?: throw Unreadable("This JSON isn't a reading-progress export.")
        return rows.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            fun text(vararg keys: String) = keys.firstNotNullOfOrNull { k -> o[k]?.str() }.orEmpty()
            fun number(vararg keys: String) = keys.firstNotNullOfOrNull { k -> o[k]?.int() }
            BackupNovel(
                title = text("title").trim(),
                read = number("chapters_read", "read_count", "read_chapters") ?: return@mapNotNull null,
                total = number("total_chapters") ?: 0,
                author = text("author").trim(),
                site = text("site").trim(),
                position = number("current_position"),
            )
        }
    }

    private fun JsonElement.str(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
    private fun JsonElement.int(): Int? = runCatching { jsonPrimitive.doubleOrNull?.toInt() }.getOrNull()

    private fun fromCsv(body: String): List<BackupNovel> {
        val rows = csvRows(body)
        val head = rows.firstOrNull()?.map { it.trim().lowercase() }.orEmpty()
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> head.indexOf(n).takeIf { it >= 0 } }
        val title = col("title")
        val read = col("read_chapters", "chapters read", "chapters_read", "read_count")
        if (title == null || read == null) throw Unreadable("This file isn't a reading-progress export.")
        val total = col("total_chapters", "total chapters")
        val author = col("author")
        val site = col("site")
        val position = col("current chapter #", "current_position")
        return rows.drop(1).mapNotNull { r ->
            fun at(i: Int?) = i?.let { r.getOrNull(it) }?.trim().orEmpty()
            BackupNovel(
                title = at(title),
                read = at(read).toDoubleOrNull()?.toInt() ?: return@mapNotNull null,
                total = at(total).toDoubleOrNull()?.toInt() ?: 0,
                author = at(author),
                site = at(site),
                position = at(position).toDoubleOrNull()?.toInt(),
            )
        }
    }

    /** RFC 4180: fields may be quoted, and a quoted one may hold commas, doubled quotes and line breaks. */
    internal fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (text.getOrNull(i + 1) == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row += field.toString(); field.clear() }
                '\r' -> {}
                '\n' -> { row += field.toString(); field.clear(); rows += row; row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }
}

/**
 * Puts a backup's reading progress back: for people whose account went with
 * the server, or who start again on a new device with only the export.
 *
 * A novel in the library under the same title (exactly, as [TitleMatch] means
 * it) gets its first chapters marked read, as many as the backup counted, and,
 * if it has no reading position yet, one at the chapter the backup was at.
 * Nothing already read is unmarked. Novels not in the library wait in [store]
 * until they are added, found from the waiting list ([restoreFound]) or added
 * any other way under the same title ([matchWaiting]), or until forgotten.
 */
class StatsRestore(private val lib: LibraryStore, private val store: KeyValueStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(BackupNovel.serializer())
    private val lock = Mutex()

    private val _waiting = MutableStateFlow(load())
    /** Novels from a backup not in the library yet. */
    val waiting: StateFlow<List<BackupNovel>> = _waiting.asStateFlow()

    private val _adding = MutableStateFlow(false)
    /** A novel picked from a search is being added ([restoreFound]): its chapter list can take a moment. */
    val adding: StateFlow<Boolean> = _adding.asStateFlow()

    /** What an import did: novels whose progress was put back, and novels left waiting. */
    data class Outcome(val restored: Int, val waiting: Int)

    suspend fun import(novels: List<BackupNovel>): Outcome = lock.withLock {
        val books = lib.libraryFlowOnce()
        var restored = 0
        val left = mutableListOf<BackupNovel>()
        for (n in merged(novels.filter { it.read > 0 })) {
            val found = matches(n, books).filter { restoreInto(it.id, n) }
            if (found.isEmpty()) left += n else restored++
        }
        keep(merged(_waiting.value + left))
        Outcome(restored, left.size)
    }

    /**
     * Waiting novels that are in [books] now, restored and off the list; called
     * whenever the library changes while anything waits. Returns what was restored.
     */
    suspend fun matchWaiting(books: List<LibBook>): List<BackupNovel> = lock.withLock {
        val done = _waiting.value.filter { n -> matches(n, books).filter { restoreInto(it.id, n) }.isNotEmpty() }
        if (done.isNotEmpty()) keep(_waiting.value - done.toSet())
        done
    }

    /**
     * [n] is novel [bookId], as chosen from a search: its chapter list is
     * fetched if it has none yet, then it joins the library with the backup's
     * progress. A novel whose source lists no chapters stays out. False when
     * [n] no longer waits (restored meanwhile), and nothing is done.
     */
    suspend fun restoreFound(bookId: Int, n: BackupNovel): Boolean = lock.withLock {
        if (n !in _waiting.value) return@withLock false
        _adding.value = true
        try {
            val book = lib.book(bookId) ?: error("No such novel")
            if (lib.progress(bookId).total == 0) lib.refresh(bookId)
            if (lib.progress(bookId).total == 0) error("The source listed no chapters for it.")
            if (!book.inLibrary) lib.setInLibrary(bookId, true)
            restoreInto(bookId, n)
            keep(_waiting.value - n)
            true
        } finally {
            _adding.value = false
        }
    }

    fun forget(n: BackupNovel) { keep(_waiting.value - n) }

    fun forgetAll() { keep(emptyList()) }

    /** The library novels [n] is: the same title; the same site too, when that settles which. */
    private fun matches(n: BackupNovel, books: List<LibBook>): List<LibBook> {
        val same = books.filter { TitleMatch.of(n.title, it.title) == TitleMatch.Kind.Exact }
        if (same.size < 2 || n.site.isBlank()) return same
        return same.filter { it.site.equals(n.site, ignoreCase = true) }.ifEmpty { same }
    }

    /** Mark [n]'s chapters read in novel [bookId]; false when it has no chapters to mark yet. */
    private suspend fun restoreInto(bookId: Int, n: BackupNovel): Boolean {
        val p = lib.progress(bookId)
        if (p.total == 0) return false
        val upTo = minOf(n.read, p.total)
        lib.setRead(bookId, (1..upTo).filter { it !in p.readPositions }, true)
        if (p.lastPosition == null) {
            val at = (n.position?.takeIf { it > 1 } ?: (upTo + 1)).coerceIn(1, p.total)
            lib.saveProgress(bookId, at, 0f, null)
        }
        return true
    }

    /** One entry per title, the furthest read kept. */
    private fun merged(novels: List<BackupNovel>): List<BackupNovel> =
        novels.groupBy { TitleMatch.normalize(it.title) }.values.map { same -> same.maxBy { it.read } }

    private fun load(): List<BackupNovel> =
        store.getString(KEY, null)?.let { runCatching { json.decodeFromString(list, it) }.getOrNull() }.orEmpty()

    private fun keep(novels: List<BackupNovel>) {
        _waiting.value = novels
        if (novels.isEmpty()) store.remove(KEY) else store.putString(KEY, json.encodeToString(list, novels))
    }

    private companion object { const val KEY = "waiting" }
}
