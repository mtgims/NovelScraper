package com.novelscraper.app.library

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.ChapterRead
import com.novelscraper.app.data.SyncRequest
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
 * Signed in, the library syncs with the user's other devices ([syncNow], see
 * [SyncRecords]): novels in the library, their order, ratings, reading position
 * (to the sentence), read marks and collections, never chapter text. Novels
 * stored on the server are found by [pullServer]; deleting one goes through a
 * small outbox ([flushOutbox]) as it deletes it on the server.
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
    // encodeDefaults: a sync record spells out every field, so "read: false"
    // travels as {"read":false} rather than an empty object.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
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
            LibProgress(r?.position?.toInt(), r?.scroll?.toFloat() ?: 0f, read.map { it.toInt() }.toSet(),
                counts?.total?.toInt() ?: 0, r?.sentence?.toInt())
        }

    /** The library as it is now (for a one-off pass, not a screen). */
    suspend fun libraryFlowOnce(): List<LibBook> = io {
        val members = shelves.memberships().executeAsList().groupBy({ it.book_id.toInt() }, { it.collection_id.toInt() })
        books.selectLibrary().executeAsList().map { it.toLib(members[it.id.toInt()].orEmpty()) }
    }

    suspend fun book(id: Int): LibBook? = io {
        books.selectById(id.toLong()).executeAsOneOrNull()
            ?.toLib(shelves.ofBook(id.toLong()).executeAsList().map { it.toInt() })
    }

    suspend fun progress(id: Int): LibProgress = io {
        val r = books.resume(id.toLong()).executeAsOneOrNull()
        val read = chapters.readPositions(id.toLong()).executeAsList().map { it.toInt() }.toSet()
        LibProgress(r?.position?.toInt(), r?.scroll?.toFloat() ?: 0f, read,
            chapters.counts(id.toLong()).executeAsOne().total.toInt(), r?.sentence?.toInt())
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
                    val id = books.lastInsertId().executeAsOne()
                    // Read on another device before: its progress and rating apply here too.
                    books.selectById(id).executeAsOneOrNull()?.let { sync.reapplyNovel(it) }
                    id.toInt()
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
                // Read marks and the resume point from other devices, for chapters now here.
                books.selectById(b.id).executeAsOneOrNull()?.let { sync.reapplyNovel(it) }
            }
        }
    }

    /** Add or remove a novel from the library (it stays on the device either way
     *  while it has downloads). */
    suspend fun setInLibrary(id: Int, inLibrary: Boolean) = change {
        val order = if (inLibrary) books.maxSortOrder().executeAsOne() + 1 else 0
        books.setInLibrary(if (inLibrary) 1 else 0, if (inLibrary) now() else 0, order, id.toLong())
        val b = books.selectById(id.toLong()).executeAsOneOrNull() ?: return@change
        if (b.sync_key == null) return@change
        // Joining the library shares everything about it; leaving, just that.
        if (inLibrary) sync.emitSnapshot(b) else sync.emitNovel(b)
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
    suspend fun markOpened(id: Int, position: Int) = change {
        val c = chapters.selectAt(id.toLong(), position.toLong()).executeAsOneOrNull() ?: return@change
        val b = books.selectById(id.toLong()).executeAsOneOrNull() ?: return@change
        // Re-opening the same chapter keeps its scroll and sentence (the reader restores them).
        val same = b.last_chapter_id == c.id
        books.setResume(c.id, if (same) b.scroll else 0.0, id.toLong())
        if (!same) books.setSentence(null, id.toLong())
        chapters.setRead(1, id.toLong(), position.toLong())
        books.setOpened(now(), id.toLong())
        syncing(id) { nb -> sync.emitProgress(nb); sync.emitRead(nb, c.path, true) }
    }

    /** Where in the chapter the reader is: the scroll (0..1) and/or the sentence
     *  (narration knows the sentence; the reader both). The chapter becomes the
     *  resume point. */
    suspend fun saveProgress(id: Int, position: Int, fraction: Float?, sentence: Int?) = change {
        val c = chapters.selectAt(id.toLong(), position.toLong()).executeAsOneOrNull() ?: return@change
        val b = books.selectById(id.toLong()).executeAsOneOrNull() ?: return@change
        val scroll = fraction?.toDouble() ?: if (b.last_chapter_id == c.id) b.scroll else 0.0
        if (b.last_chapter_id == c.id && b.scroll == scroll && b.sentence?.toInt() == sentence) return@change
        books.setResume(c.id, scroll, id.toLong())
        books.setSentence(sentence?.toLong(), id.toLong())
        syncing(id) { nb -> sync.emitProgress(nb) }
    }

    suspend fun saveScroll(id: Int, position: Int, fraction: Float) = saveProgress(id, position, fraction, null)

    suspend fun setRead(id: Int, positions: List<Int>, read: Boolean) {
        if (positions.isEmpty()) return
        change {
            positions.chunked(500).forEach { chunk ->
                chapters.setReadIn(if (read) 1 else 0, id.toLong(), chunk.map { it.toLong() })
            }
            syncing(id) { b ->
                val wanted = positions.toSet()
                chapters.selectReadState(id.toLong()).executeAsList()
                    .filter { it.position.toInt() in wanted }
                    .forEach { sync.emitRead(b, it.path, read) }
            }
        }
    }

    suspend fun markAllRead(id: Int) = change {
        chapters.setAllRead(1, id.toLong())
        syncing(id) { b -> chapters.selectReadState(id.toLong()).executeAsList().forEach { sync.emitRead(b, it.path, true) } }
    }

    suspend fun resetProgress(id: Int) = change {
        val wasRead = chapters.selectReadState(id.toLong()).executeAsList().filter { it.read != 0L }
        chapters.setAllRead(0, id.toLong())
        books.setResume(null, 0.0, id.toLong())
        books.setSentence(null, id.toLong())
        syncing(id) { b ->
            wasRead.forEach { sync.emitRead(b, it.path, false) }
            sync.emitProgress(b)
        }
    }

    // --- metadata ------------------------------------------------------------

    suspend fun setRating(id: Int, rating: Int) = change {
        val r = rating.takeIf { it in 1..5 }
        books.setRating(r?.toLong(), id.toLong())
        syncing(id) { b -> sync.emit(Kind.RATING, b.sync_key!!, RatingRec.serializer(), RatingRec(r)) }
    }

    /** The novels' new order (the library's "All" tab). */
    suspend fun reorder(ids: List<Int>) = change {
        ids.forEachIndexed { i, id ->
            val order = i.toLong() + 1
            val b = books.selectById(id.toLong()).executeAsOneOrNull() ?: return@forEachIndexed
            if (b.sort_order == order) return@forEachIndexed
            books.setSortOrder(order, id.toLong())
            syncing(id) { nb -> sync.emit(Kind.ORDER, nb.sync_key!!, OrderRec.serializer(), OrderRec(order)) }
        }
    }

    suspend fun createCollection(name: String): Int = changeWithResult {
        val key = java.util.UUID.randomUUID().toString()
        val order = shelves.maxSortOrder().executeAsOne() + 1
        shelves.insert(name.trim(), order, null, key)
        val id = shelves.lastInsertId().executeAsOne().toInt()   // before emit, which inserts too
        sync.emit(Kind.COLLECTION, key, CollectionRec.serializer(), CollectionRec(name.trim(), order))
        id
    }

    suspend fun renameCollection(id: Int, name: String) = change {
        shelves.rename(name.trim(), id.toLong())
        val c = shelves.selectAll().executeAsList().firstOrNull { it.id == id.toLong() } ?: return@change
        c.sync_key?.let { sync.emit(Kind.COLLECTION, it, CollectionRec.serializer(), CollectionRec(c.name, c.sort_order)) }
    }

    suspend fun deleteCollection(id: Int) = change {
        val c = shelves.selectAll().executeAsList().firstOrNull { it.id == id.toLong() } ?: return@change
        shelves.delete(id.toLong())
        c.sync_key?.let {
            sync.emit(Kind.COLLECTION, it, CollectionRec.serializer(), CollectionRec(c.name, c.sort_order, deleted = true))
        }
    }

    suspend fun setBookCollections(id: Int, collectionIds: List<Int>) = change {
        val before = shelves.ofBook(id.toLong()).executeAsList().map { it.toInt() }.toSet()
        val after = collectionIds.toSet()
        shelves.clearBook(id.toLong())
        after.forEach { shelves.add(id.toLong(), it.toLong()) }
        syncing(id) { b ->
            val keys = shelves.selectAll().executeAsList().associate { it.id.toInt() to it.sync_key }
            (after - before).forEach { cid -> keys[cid]?.let { sync.emitShelf(it, b.sync_key!!, true) } }
            (before - after).forEach { cid -> keys[cid]?.let { sync.emitShelf(it, b.sync_key!!, false) } }
        }
    }

    /** Remove a novel from this device, with its downloads. A server novel is
     *  deleted on the server too (and so from the other devices). */
    suspend fun delete(id: Int) {
        val sid = serverIdOf(id)
        change {
            val b = books.selectById(id.toLong()).executeAsOneOrNull() ?: return@change
            if (b.sync_key != null && sync.isSyncable(b)) sync.emitNovel(b.copy(in_library = 0))
            books.delete(id.toLong())
        }
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
    fun failedForBookFlow(id: Int): Flow<Long> =
        chapters.failedForBook(id.toLong()).asFlow().mapToOneOrNull(Dispatchers.IO).map { it ?: 0 }

    fun queuedForBookFlow(id: Int): Flow<Long> =
        chapters.queuedForBook(id.toLong()).asFlow().mapToOneOrNull(Dispatchers.IO).map { it ?: 0 }

    /** The next chapter to download: (chapter row id, novel id, tries so far). */
    internal suspend fun nextQueued(): Triple<Long, Int, Long>? = io {
        chapters.nextQueued().executeAsOneOrNull()?.let { Triple(it.chapter_id, it.book_id.toInt(), it.attempts) }
    }

    /** A download didn't work: count the try, and give up on it after [tries]. */
    internal suspend fun downloadFailed(chapterId: Long, attempts: Long, tries: Int): Boolean = io {
        val giveUp = attempts + 1 >= tries
        chapters.attemptFailed(if (giveUp) 1 else 0, chapterId)
        giveUp
    }

    /** Chapters whose download gave up. */
    fun failedDownloadsFlow(): Flow<Long> =
        chapters.failedCount().asFlow().mapToOneOrNull(Dispatchers.IO).map { it ?: 0 }

    suspend fun retryFailedDownloads() = io { chapters.retryFailed() }

    suspend fun forgetFailedDownloads() = io { chapters.dropFailed() }

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

    // --- sync ------------------------------------------------------------------

    internal val sync = SyncRecords(db, json, now)

    /** Called after a local change that other devices should get (the app
     *  schedules a sync). */
    var onLocalChange: () -> Unit = {}

    /** A local change, in one transaction; [onLocalChange] afterwards. */
    private suspend fun change(block: () -> Unit) {
        io { db.transaction { block() } }
        onLocalChange()
    }

    private suspend fun <T> changeWithResult(block: () -> T): T {
        val r = io { db.transactionWithResult { block() } }
        onLocalChange()
        return r
    }

    /** [block] with the novel's row, if the novel is one that syncs. */
    private fun syncing(id: Int, block: (Book) -> Unit) {
        val b = books.selectById(id.toLong()).executeAsOneOrNull() ?: return
        if (sync.isSyncable(b)) block(b)
    }

    /**
     * Sync with the server: send what changed here (the whole library, the first
     * time), and apply what changed on the other devices. Returns false if not
     * signed in; throws if the server can't be reached (nothing is lost: what
     * wasn't accepted is sent next time).
     */
    /** Raised when the server answers, but has no sync in it: an older build. */
    class SyncUnsupportedException : Exception("This server doesn't have sync yet.")

    suspend fun syncNow(): Boolean = serverMutex.withLock {
        if (!server.enabled) return false
        io { db.transaction { sync.enroll() } }
        repeat(MAX_SYNC_ROUNDS) {
            val (cursor, device, out) = io { db.transactionWithResult { Triple(sync.cursor(), sync.device(), sync.pending(SYNC_BATCH)) } }
            val resp = try {
                server.sync(SyncRequest(cursor, device, out))
            } catch (e: retrofit2.HttpException) {
                // A 404 is not a network problem: the server is answering, it
                // simply doesn't know this endpoint. Saying "can't reach the
                // server" for that sends everyone looking in the wrong place.
                if (e.code() == 404) throw SyncUnsupportedException() else throw e
            }
            io {
                db.transaction {
                    sync.sent(out)
                    sync.applyIncoming(resp.changes)
                    sync.setCursor(resp.cursor)
                }
            }
            if (out.size < SYNC_BATCH && !resp.more) return true
        }
        true
    }

    /** Changes here not yet accepted by the server. */
    suspend fun pendingSyncCount(): Long = io { sync.pendingCount() }

    /** Signed out, or into another account: its records are not this one's. */
    suspend fun resetSync() = serverMutex.withLock { io { db.transaction { sync.reset() } } }

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
        val added = io {
            db.transactionWithResult {
                val known = books.selectServerIds().executeAsList().associate { it.server_id.toInt() to it.id }
                val added = ArrayList<Pair<Long, Int>>()
                var order = books.maxSortOrder().executeAsOne()
                for (r in remote) {
                    val meta = json.encodeToString(BookRead.serializer(), r)
                    val localId = known[r.id]
                    if (localId != null) {
                        books.updateServerMeta(meta, r.title, r.author, r.site, localId)
                        // Arrived by sync from another device: its chapters are still to fetch.
                        if (books.selectById(localId).executeAsOneOrNull()?.checked_at == 0L) added += localId to r.id
                        continue
                    }
                    books.insertServer(r.id.toLong(), meta, r.title, r.author, r.site, r.rating?.toLong(), ++order, now())
                    val id = books.lastInsertId().executeAsOne()
                    added += id to r.id
                    books.selectById(id).executeAsOneOrNull()?.let { b ->
                        // New on the server (scraped or imported there): tell the other
                        // devices, and take what they already know about it.
                        sync.emitNovel(b)
                        sync.reapplyNovel(b)
                    }
                }
                val live = remote.map { it.id }.toSet()
                known.filterKeys { it !in live }.values.forEach { books.delete(it) }
                added
            }
        }
        // Chapter lists for the new ones (best effort; opening a novel fetches
        // them again).
        for ((localId, serverId) in added) {
            runCatching { refreshServerLocked(localId.toInt(), serverId) }
                .onFailure { Log.w(TAG, "import server book $serverId: ${it.message}") }
        }
        if (added.isNotEmpty()) onLocalChange()
        added.size
    }

    private suspend fun refreshServer(id: Int, serverId: Int): Unit = serverMutex.withLock {
        if (!server.enabled) return
        refreshServerLocked(id, serverId)
    }

    /** A server novel's details and chapter list. (Its progress, rating and
     *  shelves come through sync.) */
    private suspend fun refreshServerLocked(id: Int, serverId: Int) {
        val meta = server.book(serverId)
        val list = server.chapters(serverId)
        io {
            db.transaction {
                books.updateServerMeta(json.encodeToString(BookRead.serializer(), meta),
                    meta.title, meta.author, meta.site, id.toLong())
                mergeChapters(id.toLong(), list.map {
                    ChapterRow(it.position.toString(), it.number, it.title, it.volume.toLong(), null)
                })
                books.setCheckedAt(now(), id.toLong())
                books.selectById(id.toLong()).executeAsOneOrNull()?.let { sync.reapplyNovel(it) }
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
        private const val SYNC_BATCH = 500L
        private const val MAX_SYNC_ROUNDS = 40
    }
}
