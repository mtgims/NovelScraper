package com.novelscraper.app.library

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.novelscraper.app.data.BookCollectionsUpdate
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.BookUpdate
import com.novelscraper.app.data.CollectionUpdate
import com.novelscraper.app.data.ChapterRead
import com.novelscraper.app.data.ProgressUpdate
import com.novelscraper.app.db.Book
import com.novelscraper.app.db.LibraryDb
import com.novelscraper.app.db.SelectLibrary
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The library, kept on this device: every novel, its chapter list, downloaded and
 * recently read chapter text, reading progress, ratings and collections. Screens
 * and narration read and write here; nothing waits for a server.
 *
 * Chapter text comes from the novel's origin when it is not stored yet: its
 * extension ([SourceOrigin]) for source novels, the NovelScraper server
 * ([ServerOrigin]) for novels scraped or imported there. Text read while
 * streaming is cached (the newest [CACHE_CHAPTERS] chapters); downloads stay.
 *
 * Server novels are imported by [pullServer]. Changes made here to one of them
 * (progress, rating, shelves, deletion) are applied locally at once and queued in
 * an outbox that [flushOutbox] sends in order, so the server, and the other
 * devices reading from it, stay in step (until metadata sync replaces this).
 */
class LibraryStore(
    driver: SqlDriver,
    private val sources: SourceOrigin,
    private val server: ServerOrigin,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val db = LibraryDb(driver)
    private val books get() = db.bookQueries
    private val chapters get() = db.chapterQueries
    private val shelves get() = db.collectionQueries
    private val outbox get() = db.outboxQueries

    /** Background work (outbox sends, cache pruning). */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val serverMutex = Mutex()

    // --- reading the library -------------------------------------------------

    /** The library grid: novels added to the library, in the user's order. */
    fun libraryFlow(): Flow<List<LibBook>> =
        combine(
            books.selectLibrary().asFlow().mapToList(Dispatchers.IO),
            shelves.memberships().asFlow().mapToList(Dispatchers.IO),
        ) { rows, members ->
            val byBook = members.groupBy({ it.book_id.toInt() }, { it.collection_id.toInt() })
            rows.map { it.toLib(byBook[it.id.toInt()].orEmpty()) }
        }

    fun collectionsFlow(): Flow<List<LibCollection>> =
        shelves.selectAll().asFlow().mapToList(Dispatchers.IO).map { list ->
            list.map { LibCollection(it.id.toInt(), it.name, it.sort_order.toInt(), it.server_id?.toInt()) }
        }

    fun bookFlow(id: Int): Flow<LibBook?> =
        combine(
            books.selectById(id.toLong()).asFlow().mapToOneOrNull(Dispatchers.IO),
            shelves.ofBook(id.toLong()).asFlow().mapToList(Dispatchers.IO),
        ) { b, cols -> b?.toLib(cols.map { it.toInt() }) }

    fun chaptersFlow(id: Int): Flow<List<LibChapter>> =
        chapters.selectByBook(id.toLong()).asFlow().mapToList(Dispatchers.IO).map { list ->
            list.map {
                LibChapter(it.position.toInt(), it.number, it.title, it.volume.toInt(),
                    it.read != 0L, it.downloaded != 0L, it.release_time)
            }
        }

    fun progressFlow(id: Int): Flow<LibProgress> =
        combine(
            books.resume(id.toLong()).asFlow().mapToOneOrNull(Dispatchers.IO),
            chapters.readPositions(id.toLong()).asFlow().mapToList(Dispatchers.IO),
            chapters.counts(id.toLong()).asFlow().mapToOneOrNull(Dispatchers.IO),
        ) { r, read, counts ->
            LibProgress(r?.position?.toInt(), r?.scroll?.toFloat() ?: 0f, read.map { it.toInt() }.toSet(), counts?.total?.toInt() ?: 0)
        }

    suspend fun book(id: Int): LibBook? = io {
        books.selectById(id.toLong()).executeAsOneOrNull()
            ?.toLib(shelves.ofBook(id.toLong()).executeAsList().map { it.toInt() })
    }

    suspend fun progress(id: Int): LibProgress = io {
        val r = books.resume(id.toLong()).executeAsOneOrNull()
        val read = chapters.readPositions(id.toLong()).executeAsList().map { it.toInt() }.toSet()
        LibProgress(r?.position?.toInt(), r?.scroll?.toFloat() ?: 0f, read, chapters.counts(id.toLong()).executeAsOne().total.toInt())
    }

    // --- source novels -------------------------------------------------------

    /** The local id of a source novel, adding a row (outside the library) the
     *  first time it is opened. [name] and [cover] come from the list it was
     *  picked from and show until the novel's page is fetched. */
    suspend fun openSource(pluginId: String, path: String, name: String, cover: String?): Int {
        io { books.selectBySource(pluginId, path).executeAsOneOrNull() }?.let {
            io { books.setOpened(now(), it.id) }
            return it.id.toInt()
        }
        val site = runCatching { sources.name(pluginId) }.getOrDefault("")
        return io {
            db.transactionWithResult {
                books.selectBySource(pluginId, path).executeAsOneOrNull()?.id?.toInt() ?: run {
                    books.insertSource(pluginId, path, name, "", cover, site, null, now())
                    books.lastInsertId().executeAsOne().toInt()
                }
            }
        }
    }

    /** Fetch the novel's details and chapter list from its origin again. */
    suspend fun refresh(id: Int) {
        val b = io { books.selectById(id.toLong()).executeAsOneOrNull() } ?: return
        when {
            b.plugin_id != null && b.path != null -> refreshSource(b, b.plugin_id, b.path)
            b.server_id != null -> refreshServer(b.id.toInt(), b.server_id.toInt())
        }
    }

    private suspend fun refreshSource(b: Book, pluginId: String, path: String) {
        val novel = sources.novel(pluginId, path)
        val web = runCatching { sources.webUrl(pluginId, path) }.getOrNull()
        io {
            db.transaction {
                books.updateSourceMeta(
                    title = novel.name.ifBlank { b.title },
                    author = novel.author.orEmpty(),
                    cover = novel.cover ?: b.cover,
                    summary = novel.summary.orEmpty().trim(),
                    genres = novel.genres.orEmpty(),
                    status = novel.status.orEmpty(),
                    web_url = web,
                    id = b.id,
                )
                mergeChapters(b.id, novel.chapters.distinctBy { it.path }.map { it.toRow() })
                books.setCheckedAt(now(), b.id)
            }
        }
    }

    /** Add or remove a novel from the library (it stays on the device either way
     *  while it has downloads). */
    suspend fun setInLibrary(id: Int, inLibrary: Boolean) = io {
        val order = if (inLibrary) books.maxSortOrder().executeAsOne() + 1 else 0
        books.setInLibrary(if (inLibrary) 1 else 0, if (inLibrary) now() else 0, order, id.toLong())
    }

    // --- reading -------------------------------------------------------------

    /** A chapter's text: stored, else fetched from the novel's origin (and cached). */
    suspend fun chapter(id: Int, position: Int): ChapterRead {
        val (b, c, stored) = io {
            val b = books.selectById(id.toLong()).executeAsOneOrNull() ?: error("No such novel")
            val c = chapters.selectAt(id.toLong(), position.toLong()).executeAsOneOrNull()
                ?: error("No chapter $position")
            Triple(b, c, chapters.content(c.id).executeAsOneOrNull())
        }
        val html = stored ?: fetchContent(b, c.path, position).also { text ->
            io { chapters.putContent(c.id, text, 0, now()) }
            scope.launch { runCatching { chapters.pruneCache(CACHE_CHAPTERS) } }
        }
        val max = io { chapters.maxPosition(id.toLong()).executeAsOne() }
        return ChapterRead(position, c.number, c.title, html, has_prev = position > 1, has_next = position < max)
    }

    /** Fetch one chapter's text from the novel's origin, without storing it. */
    internal suspend fun fetchContent(b: Book, chapterPath: String, position: Int): String = when {
        b.plugin_id != null -> sources.chapter(b.plugin_id, chapterPath)
        b.server_id != null -> server.chapter(b.server_id.toInt(), position)
        else -> error("This novel has no source")
    }

    /** Opening a chapter: it becomes the resume point and counts as read. */
    suspend fun markOpened(id: Int, position: Int) {
        io {
            db.transaction {
                val c = chapters.selectAt(id.toLong(), position.toLong()).executeAsOneOrNull() ?: return@transaction
                val b = books.selectById(id.toLong()).executeAsOneOrNull() ?: return@transaction
                // Keep the scroll when re-opening the same chapter (restored by the reader).
                books.setResume(c.id, if (b.last_chapter_id == c.id) b.scroll else 0.0, id.toLong())
                chapters.setRead(1, id.toLong(), position.toLong())
                books.setOpened(now(), id.toLong())
            }
        }
        toServer(id) { ProgressUpdate(last_position = position, mark_read = position) }
    }

    suspend fun saveScroll(id: Int, position: Int, fraction: Float) {
        io {
            val c = chapters.selectAt(id.toLong(), position.toLong()).executeAsOneOrNull() ?: return@io
            books.setResume(c.id, fraction.toDouble(), id.toLong())
        }
        toServer(id) { ProgressUpdate(last_position = position, scroll = fraction) }
    }

    suspend fun setRead(id: Int, positions: List<Int>, read: Boolean) {
        if (positions.isEmpty()) return
        io {
            db.transaction {
                positions.chunked(500).forEach { chunk ->
                    chapters.setReadIn(if (read) 1 else 0, id.toLong(), chunk.map { it.toLong() })
                }
            }
        }
        toServer(id) {
            if (read) ProgressUpdate(mark_positions = positions) else ProgressUpdate(unmark_positions = positions)
        }
    }

    suspend fun markAllRead(id: Int) {
        io { chapters.setAllRead(1, id.toLong()) }
        toServer(id) { ProgressUpdate(mark_all = true) }
    }

    suspend fun resetProgress(id: Int) {
        io {
            db.transaction {
                chapters.setAllRead(0, id.toLong())
                books.setResume(null, 0.0, id.toLong())
            }
        }
        toServer(id) { ProgressUpdate(reset = true) }
    }

    // --- metadata ------------------------------------------------------------

    suspend fun setRating(id: Int, rating: Int) {
        io { books.setRating(rating.takeIf { it > 0 }?.toLong(), id.toLong()) }
        serverIdOf(id)?.let { sid ->
            queue(ServerOutbox.RATING, sid, json.encodeToString(BookUpdate.serializer(),
                BookUpdate(rating = rating)))
        }
    }

    /** The novels' new order (the library's "All" tab). */
    suspend fun reorder(ids: List<Int>) = io {
        db.transaction { ids.forEachIndexed { i, id -> books.setSortOrder(i.toLong() + 1, id.toLong()) } }
    }

    suspend fun createCollection(name: String): Int = io {
        db.transactionWithResult {
            shelves.insert(name.trim(), shelves.maxSortOrder().executeAsOne() + 1, null)
            shelves.lastInsertId().executeAsOne().toInt()
        }
    }

    suspend fun renameCollection(id: Int, name: String) {
        val serverId = io {
            shelves.rename(name.trim(), id.toLong())
            shelves.selectAll().executeAsList().firstOrNull { it.id == id.toLong() }?.server_id
        }
        if (serverId != null) queue(ServerOutbox.RENAME_COLLECTION, serverId.toInt(),
            json.encodeToString(CollectionUpdate.serializer(),
                CollectionUpdate(name = name.trim())))
    }

    suspend fun deleteCollection(id: Int) {
        val serverId = io {
            val sid = shelves.selectAll().executeAsList().firstOrNull { it.id == id.toLong() }?.server_id
            shelves.delete(id.toLong())
            sid
        }
        if (serverId != null) queue(ServerOutbox.DELETE_COLLECTION, serverId.toInt(), "{}")
    }

    suspend fun setBookCollections(id: Int, collectionIds: List<Int>) {
        val serverShelves = io {
            db.transactionWithResult {
                shelves.clearBook(id.toLong())
                collectionIds.forEach { shelves.add(id.toLong(), it.toLong()) }
                shelves.selectAll().executeAsList()
                    .filter { it.id.toInt() in collectionIds }
                    .mapNotNull { it.server_id?.toInt() }
            }
        }
        serverIdOf(id)?.let { sid ->
            queue(ServerOutbox.BOOK_COLLECTIONS, sid,
                json.encodeToString(BookCollectionsUpdate.serializer(),
                    BookCollectionsUpdate(serverShelves)))
        }
    }

    /** Remove a novel from this device, with its downloads. A server novel is
     *  deleted on the server too. */
    suspend fun delete(id: Int) {
        val sid = serverIdOf(id)
        io { books.delete(id.toLong()) }
        if (sid != null) queue(ServerOutbox.DELETE_BOOK, sid, "{}")
    }

    // --- downloads -----------------------------------------------------------

    /** Queue chapters for download (all of them when [positions] is null). */
    suspend fun enqueueDownloads(id: Int, positions: List<Int>?) = io {
        db.transaction {
            val rows = chapters.selectByBook(id.toLong()).executeAsList()
            val wanted = positions?.toSet()
            val t = now()
            rows.filter { it.downloaded == 0L && (wanted == null || it.position.toInt() in wanted) }
                .forEach { chapters.enqueue(it.id, t) }
        }
    }

    suspend fun removeDownloads(id: Int) = io {
        db.transaction {
            chapters.clearQueueForBook(id.toLong())
            chapters.removeDownloads(id.toLong())
        }
    }

    suspend fun cancelDownloads(id: Int) = io { chapters.clearQueueForBook(id.toLong()) }

    fun queuedFlow(): Flow<Long> = chapters.queuedCount().asFlow().mapToOneOrNull(Dispatchers.IO).map { it ?: 0 }
    fun queuedForBookFlow(id: Int): Flow<Long> =
        chapters.queuedForBook(id.toLong()).asFlow().mapToOneOrNull(Dispatchers.IO).map { it ?: 0 }

    /** The next queued chapter: (chapter row id, novel id, position). */
    internal suspend fun nextQueued(): Triple<Long, Int, Int>? = io {
        chapters.nextQueued().executeAsOneOrNull()?.let { Triple(it.chapter_id, it.book_id.toInt(), it.position.toInt()) }
    }

    /** Download one queued chapter (keeping text already cached) and take it off
     *  the queue. True if it was fetched from a source site (for pacing). */
    internal suspend fun downloadQueued(chapterId: Long): Boolean {
        val (b, c, cached) = io {
            val c = chapters.selectById(chapterId).executeAsOneOrNull()
            val b = c?.let { books.selectById(it.book_id).executeAsOneOrNull() }
            Triple(b, c, c?.let { chapters.content(it.id).executeAsOneOrNull() })
        }
        if (b == null || c == null) { io { chapters.dequeue(chapterId) }; return false }
        val html = cached ?: fetchContent(b, c.path, c.position.toInt())
        io {
            db.transaction {
                chapters.putContent(c.id, html, 1, now())
                chapters.dequeue(c.id)
            }
        }
        return cached == null && b.plugin_id != null
    }

    // --- the server ----------------------------------------------------------

    /**
     * Import the server's library: new novels are added (with their chapter lists
     * and progress), known ones updated, ones deleted there removed here, and the
     * server's shelves mirrored. Local changes are sent first; if they can't be,
     * nothing is pulled (the server's older state would overwrite them).
     * Returns the number of novels added.
     */
    suspend fun pullServer(): Int = serverMutex.withLock {
        if (!server.enabled) return 0
        if (!flushOutboxLocked()) return 0
        val remote = server.books()
        val cols = server.collections()
        val added = io {
            db.transactionWithResult {
                // Shelves: the server's, by server id; ones deleted there go.
                val colIds = HashMap<Int, Long>()
                for (c in cols) {
                    val local = shelves.selectByServerId(c.id.toLong()).executeAsOneOrNull()
                    colIds[c.id] = if (local == null) {
                        shelves.insert(c.name, c.sort_order.toLong(), c.id.toLong())
                        shelves.lastInsertId().executeAsOne()
                    } else {
                        if (local.name != c.name) shelves.rename(c.name, local.id)
                        local.id
                    }
                }
                val liveCols = cols.map { it.id.toLong() }.toSet()
                shelves.selectServerLinked().executeAsList()
                    .filter { it.server_id !in liveCols }
                    .forEach { shelves.delete(it.id) }

                val known = books.selectServerIds().executeAsList().associate { it.server_id.toInt() to it.id }
                val added = ArrayList<Pair<Long, Int>>()
                var order = books.maxSortOrder().executeAsOne()
                for (r in remote) {
                    val meta = json.encodeToString(BookRead.serializer(), r)
                    val localId = known[r.id] ?: run {
                        books.insertServer(r.id.toLong(), meta, r.title, r.author, r.site, r.rating?.toLong(), ++order, now())
                        books.lastInsertId().executeAsOne().also { added += it to r.id }
                    }
                    if (r.id in known) books.updateServerMeta(meta, r.title, r.author, r.site, r.rating?.toLong(), localId)
                    shelves.clearServerShelves(localId)
                    r.collection_ids.mapNotNull { colIds[it] }.forEach { shelves.add(localId, it) }
                }
                val live = remote.map { it.id }.toSet()
                known.filterKeys { it !in live }.values.forEach { books.delete(it) }
                added
            }
        }
        // Chapter lists and progress for the new ones (best effort; opening a
        // novel fetches them again).
        for ((localId, serverId) in added) {
            runCatching { refreshServerLocked(localId.toInt(), serverId) }
                .onFailure { Log.w(TAG, "import server book $serverId: ${it.message}") }
        }
        added.size
    }

    private suspend fun refreshServer(id: Int, serverId: Int): Unit = serverMutex.withLock {
        if (!server.enabled) return
        refreshServerLocked(id, serverId)
    }

    /** A server novel's details, chapter list and progress, after sending local
     *  changes (skipped, keeping local state, if they can't be sent). */
    private suspend fun refreshServerLocked(id: Int, serverId: Int) {
        if (!flushOutboxLocked()) return
        val meta = server.book(serverId)
        val list = server.chapters(serverId)
        val p = server.progress(serverId)
        io {
            db.transaction {
                books.updateServerMeta(json.encodeToString(BookRead.serializer(), meta),
                    meta.title, meta.author, meta.site, meta.rating?.toLong(), id.toLong())
                mergeChapters(id.toLong(), list.map {
                    ChapterRow(it.position.toString(), it.number, it.title, it.volume.toLong(), null)
                })
                chapters.setAllRead(0, id.toLong())
                p.read_positions.chunked(500).forEach { chunk ->
                    chapters.setReadIn(1, id.toLong(), chunk.map { it.toLong() })
                }
                val last = chapters.selectAt(id.toLong(), p.last_position.toLong()).executeAsOneOrNull()
                if (p.read_positions.isNotEmpty() || p.scroll > 0f) books.setResume(last?.id, p.scroll.toDouble(), id.toLong())
                books.setCheckedAt(now(), id.toLong())
            }
        }
    }

    /** Send queued changes now (in the background). */
    fun flushOutboxSoon() {
        scope.launch { runCatching { serverMutex.withLock { flushOutboxLocked() } } }
    }

    suspend fun flushOutbox(): Boolean = serverMutex.withLock { flushOutboxLocked() }

    /** Send queued changes in order. True when the queue is empty afterwards. */
    private suspend fun flushOutboxLocked(): Boolean {
        if (!server.enabled) return io { outbox.count().executeAsOne() } == 0L
        for (e in io { outbox.selectAll().executeAsList() }) {
            when (ServerOutbox.send(server, json, e.kind, e.target.toInt(), e.body)) {
                ServerOutbox.Result.Done, ServerOutbox.Result.Dropped -> io { outbox.delete(e.id) }
                ServerOutbox.Result.Later -> return false
            }
        }
        return true
    }

    private suspend fun toServer(id: Int, update: () -> ProgressUpdate) {
        val sid = serverIdOf(id) ?: return
        queue(ServerOutbox.PROGRESS, sid, json.encodeToString(ProgressUpdate.serializer(), update()))
    }

    private suspend fun queue(kind: String, target: Int, body: String) {
        io { outbox.add(kind, target.toLong(), body, now()) }
        flushOutboxSoon()
    }

    private suspend fun serverIdOf(id: Int): Int? =
        io { books.selectById(id.toLong()).executeAsOneOrNull()?.server_id?.toInt() }

    // --- housekeeping --------------------------------------------------------

    /** Drop novels browsed but never added (untouched for [UNUSED_DAYS] days) and
     *  old cached text. */
    fun prune() {
        scope.launch {
            runCatching {
                books.pruneUnused(now() - UNUSED_DAYS * 24L * 3600 * 1000)
                chapters.pruneCache(CACHE_CHAPTERS)
            }.onFailure { Log.w(TAG, "prune: ${it.message}") }
        }
    }

    // --- internals -----------------------------------------------------------

    private data class ChapterRow(val path: String, val number: String, val title: String, val volume: Long, val release: String?)

    private fun ChapterItem.toRow() = ChapterRow(
        path, chapterNumber?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty(),
        name, 1, releaseTime,
    )

    /** Make the book's chapters [list], in its order: known paths keep their row
     *  (and so their read state and text), new ones are added, missing ones go. */
    private fun mergeChapters(bookId: Long, list: List<ChapterRow>) {
        val existing = chapters.selectPaths(bookId).executeAsList().associate { it.path to it.id }
        list.forEachIndexed { i, ch ->
            val pos = i.toLong() + 1
            val known = existing[ch.path]
            if (known != null) chapters.updateMeta(pos, ch.number, ch.title, ch.volume, ch.release, known)
            else chapters.insert(bookId, pos, ch.path, ch.number, ch.title, ch.volume, ch.release)
        }
        val keep = list.map { it.path }.toSet()
        existing.filterKeys { it !in keep }.values.forEach { chapters.deleteById(it) }
    }

    private fun Book.toLib(cols: List<Int>) = LibBook(
        id = id.toInt(), title = title, author = author, cover = cover, site = site,
        rating = rating?.toInt(), inLibrary = in_library != 0L, pluginId = plugin_id, path = path,
        serverId = server_id?.toInt(), server = server_meta?.let { runCatching { json.decodeFromString(BookRead.serializer(), it) }.getOrNull() },
        summary = summary, genres = genres, status = status, webUrl = web_url, collectionIds = cols, checkedAt = checked_at,
    )

    private fun SelectLibrary.toLib(cols: List<Int>) = LibBook(
        id = id.toInt(), title = title, author = author, cover = cover, site = site,
        rating = rating?.toInt(), inLibrary = true, pluginId = plugin_id, path = path,
        serverId = server_id?.toInt(), server = server_meta?.let { runCatching { json.decodeFromString(BookRead.serializer(), it) }.getOrNull() },
        summary = summary, genres = genres, status = status, webUrl = web_url, collectionIds = cols,
        chapterCount = chapter_count.toInt(), unreadCount = unread_count.toInt(), downloadedCount = downloaded_count.toInt(),
        checkedAt = checked_at,
    )

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val TAG = "Library"
        /** Chapters kept from streaming (not downloaded). */
        const val CACHE_CHAPTERS = 300L
        const val UNUSED_DAYS = 30
    }
}
