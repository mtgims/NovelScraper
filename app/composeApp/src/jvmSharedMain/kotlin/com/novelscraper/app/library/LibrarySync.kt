package com.novelscraper.app.library

import com.novelscraper.app.data.SyncChange
import com.novelscraper.app.db.Book
import com.novelscraper.app.db.LibraryDb
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

// What syncs between a user's devices, as records (the server side is
// backend/app/services/sync.py, which documents the kinds and keys). Values:

@Serializable
internal data class NovelRec(
    val plugin: String? = null,
    val path: String? = null,
    val title: String = "",
    val author: String = "",
    val cover: String? = null,
    val in_library: Boolean = true,
)

@Serializable internal data class OrderRec(val sort: Long = 0)
@Serializable internal data class RatingRec(val rating: Int? = null)
/** [chapter]: the chapter's path; null = nothing opened (progress reset). */
@Serializable internal data class ProgressRec(val chapter: String? = null, val scroll: Float = 0f, val sentence: Int? = null)
@Serializable internal data class ReadRec(val read: Boolean = false)
@Serializable internal data class CollectionRec(val name: String = "", val sort: Long = 0, val deleted: Boolean = false)
@Serializable internal data class ShelfRec(val member: Boolean = false)
/** An installed source extension, so the same sources follow the account. */
@Serializable internal data class SourceRec(val repo: String = "", val enabled: Boolean = true)

internal object Kind {
    const val NOVEL = "novel"
    const val ORDER = "order"
    const val RATING = "rating"
    const val PROGRESS = "progress"
    const val READ = "read"
    const val COLLECTION = "collection"
    const val SHELF = "shelf"
    const val SOURCE = "source"
}

private const val TAB = "\t"

/**
 * The records side of the library: every local change to something that syncs
 * becomes a record here ([emit], stamped with this device's hybrid clock and
 * queued to send), and records from other devices are applied to the library
 * when they are newer than what this device has ([applyIncoming]).
 *
 * A record can arrive before what it is about (read marks for chapters not
 * fetched yet, a shelf for a novel not here yet); it is kept in sync_meta and
 * applied when that arrives ([reapplyNovel], [reapplyCollection]).
 *
 * Every function here runs inside the caller's database transaction.
 */
internal class SyncRecords(
    private val db: LibraryDb,
    private val json: Json,
    private val now: () -> Long,
    /** This device's installed sources, as (plugin id, repository). */
    private val installedSources: () -> List<Pair<String, String>> = { emptyList() },
) {
    private val q get() = db.syncQueries
    private val books get() = db.bookQueries
    private val chapters get() = db.chapterQueries
    private val shelves get() = db.collectionQueries

    /** This device's id (made on first use). */
    fun device(): String {
        q.initState(UUID.randomUUID().toString())
        return q.state().executeAsOne().device
    }

    /** The next clock value: never behind the wall clock, always past anything
     *  seen, so a change here is ordered after the ones it follows. */
    private fun tick(): Long {
        device()
        val s = q.state().executeAsOne()
        val t = maxOf(now(), s.clock + 1)
        q.setClock(t)
        return t
    }

    private fun observe(ts: Long) {
        device()
        val s = q.state().executeAsOne()
        if (ts > s.clock) q.setClock(ts)
    }

    fun <T> emit(kind: String, key: String, serializer: KSerializer<T>, value: T, ts: Long? = null) {
        // Enrollment only fills in: a record written here already keeps its clock.
        if (ts == ENROLL_TS && q.meta(kind, key).executeAsOneOrNull() != null) return
        val t = ts ?: tick()
        q.putMeta(kind, key, json.encodeToString(serializer, value), t, device())
        q.markPending(kind, key)
    }

    // --- what to emit for a novel -----------------------------------------------

    fun isSyncable(b: Book) = b.sync_key != null && b.in_library != 0L

    fun novelRec(b: Book) = NovelRec(
        plugin = b.plugin_id, path = b.path, title = b.title,
        author = b.author, cover = b.cover, in_library = b.in_library != 0L,
    )

    fun emitNovel(b: Book, ts: Long? = null) =
        emit(Kind.NOVEL, b.sync_key!!, NovelRec.serializer(), novelRec(b), ts)

    fun emitProgress(b: Book, ts: Long? = null) {
        val r = books.resume(b.id).executeAsOneOrNull()
        emit(Kind.PROGRESS, b.sync_key!!, ProgressRec.serializer(),
            ProgressRec(r?.path, r?.scroll?.toFloat() ?: 0f, r?.sentence?.toInt()), ts)
    }

    fun emitRead(b: Book, chapterPath: String, read: Boolean, ts: Long? = null) =
        emit(Kind.READ, b.sync_key!! + TAB + chapterPath, ReadRec.serializer(), ReadRec(read), ts)

    /** An installed source, named by its plugin id. */
    fun emitSource(pluginId: String, repo: String, enabled: Boolean, ts: Long? = null) =
        emit(Kind.SOURCE, pluginId, SourceRec.serializer(), SourceRec(repo, enabled), ts)

    /** The sources other devices have, as (plugin id, repository). */
    fun syncedSources(): List<Pair<String, String>> =
        q.ofKind(Kind.SOURCE).executeAsList().mapNotNull { m ->
            runCatching { json.decodeFromString(SourceRec.serializer(), m.value_) }.getOrNull()
                ?.takeIf { it.enabled && it.repo.isNotBlank() }?.let { m.key to it.repo }
        }

    fun emitShelf(collectionKey: String, novelKey: String, member: Boolean, ts: Long? = null) =
        emit(Kind.SHELF, collectionKey + TAB + novelKey, ShelfRec.serializer(), ShelfRec(member), ts)

    /** Everything about a novel: when it joins the library, or at enrollment. */
    fun emitSnapshot(b: Book, ts: Long? = null) {
        val key = b.sync_key ?: return
        emitNovel(b, ts)
        emit(Kind.ORDER, key, OrderRec.serializer(), OrderRec(b.sort_order), ts)
        if (b.rating != null) emit(Kind.RATING, key, RatingRec.serializer(), RatingRec(b.rating.toInt()), ts)
        if (b.last_chapter_id != null) emitProgress(b, ts)
        chapters.selectByBook(b.id).executeAsList().filter { it.read != 0L }
            .forEach { emitRead(b, it.path, true, ts) }
        val cols = shelves.selectAll().executeAsList().associateBy { it.id }
        shelves.ofBook(b.id).executeAsList().forEach { cid ->
            cols[cid]?.sync_key?.let { emitShelf(it, key, true, ts) }
        }
    }

    // --- enrollment: this device's library, once ---------------------------------

    /** The first sync of this device (or after an account change): send the
     *  whole library, at a clock just above the server's seed and below any real
     *  change, so it fills gaps without undoing newer changes from elsewhere. */
    fun enroll() {
        device()
        if (q.state().executeAsOne().enrolled != 0L) return
        shelves.selectAll().executeAsList().forEach { c ->
            val key = c.sync_key ?: return@forEach
            emit(Kind.COLLECTION, key, CollectionRec.serializer(), CollectionRec(c.name, c.sort_order), ENROLL_TS)
        }
        books.selectSyncable().executeAsList().forEach { id ->
            books.selectById(id).executeAsOneOrNull()?.let { emitSnapshot(it, ENROLL_TS) }
        }
        installedSources().forEach { (id, repo) -> emitSource(id, repo, true, ENROLL_TS) }
        q.setEnrolled(1)
    }

    // --- incoming --------------------------------------------------------------------

    /** Apply the other devices' changes that are newer than this device's. */
    fun applyIncoming(changes: List<SyncChange>) {
        val me = device()
        for (c in changes) {
            val local = q.meta(c.kind, c.key).executeAsOneOrNull()
            val dev = c.device.ifEmpty { me }
            if (local != null && !newer(c.ts, dev, local.ts, local.device)) continue
            q.putMeta(c.kind, c.key, c.value, c.ts, dev)
            observe(c.ts)
            runCatching { apply(c.kind, c.key, c.value) }
        }
    }

    private fun newer(ts: Long, device: String, thanTs: Long, thanDevice: String) =
        ts > thanTs || (ts == thanTs && device > thanDevice)

    private fun apply(kind: String, key: String, value: String) {
        when (kind) {
            Kind.NOVEL -> applyNovel(key, json.decodeFromString(NovelRec.serializer(), value))
            Kind.ORDER, Kind.RATING, Kind.PROGRESS -> {
                val b = books.selectBySyncKey(key).executeAsOneOrNull() ?: return
                applyAbout(b, kind, key, value)
            }
            Kind.READ -> {
                val novel = key.substringBeforeLast(TAB)
                val b = books.selectBySyncKey(novel).executeAsOneOrNull() ?: return
                applyAbout(b, kind, key, value)
            }
            Kind.COLLECTION -> applyCollection(key, json.decodeFromString(CollectionRec.serializer(), value))
            Kind.SHELF -> applyShelf(key, json.decodeFromString(ShelfRec.serializer(), value))
            // Stored only: acting on it means installing code, which is the
            // Extensions screen's job (and needs the network). See syncedSources.
            Kind.SOURCE -> {}
        }
    }

    private fun applyNovel(key: String, r: NovelRec) {
        val b = books.selectBySyncKey(key).executeAsOneOrNull()
        if (b == null) {
            if (!r.in_library) return
            val order = books.maxSortOrder().executeAsOne() + 1
            if (r.plugin == null || r.path == null) return   // nothing to read it from
            books.insertSource(r.plugin, r.path, r.title, r.author, r.cover, "", null, now())
            val id = books.lastInsertId().executeAsOne()
            books.setInLibrary(1, now(), order, id)
            books.selectBySyncKey(key).executeAsOneOrNull()?.let { reapplyNovel(it) }
            return
        }
        if (r.in_library == (b.in_library != 0L)) return
        if (r.in_library) {
            books.setInLibrary(1, now(), books.maxSortOrder().executeAsOne() + 1, b.id)
        } else {
            books.setInLibrary(0, 0, 0, b.id)
            chapters.clearQueueForBook(b.id)
            chapters.removeDownloads(b.id)
        }
    }

    /** One record about a novel that is on this device. */
    private fun applyAbout(b: Book, kind: String, key: String, value: String) {
        when (kind) {
            Kind.ORDER -> books.setSortOrder(json.decodeFromString(OrderRec.serializer(), value).sort, b.id)
            Kind.RATING -> books.setRating(
                json.decodeFromString(RatingRec.serializer(), value).rating?.takeIf { it in 1..5 }?.toLong(), b.id)
            Kind.PROGRESS -> {
                val p = json.decodeFromString(ProgressRec.serializer(), value)
                if (p.chapter == null) { books.setResume(null, 0.0, b.id); books.setSentence(null, b.id); return }
                val c = chapters.selectByPath(b.id, p.chapter).executeAsOneOrNull() ?: return
                books.setResume(c, p.scroll.toDouble(), b.id)
                books.setSentence(p.sentence?.toLong(), b.id)
            }
            Kind.READ -> {
                val path = key.substringAfterLast(TAB)
                val c = chapters.selectByPath(b.id, path).executeAsOneOrNull() ?: return
                val read = json.decodeFromString(ReadRec.serializer(), value).read
                chapters.setReadById(if (read) 1 else 0, c)
            }
        }
    }

    private fun applyCollection(key: String, r: CollectionRec) {
        val c = shelves.selectBySyncKey(key).executeAsOneOrNull()
        when {
            r.deleted -> c?.let { shelves.delete(it.id) }
            c == null -> {
                shelves.insert(r.name, r.sort, null, key)
                shelves.selectBySyncKey(key).executeAsOneOrNull()?.let { reapplyCollection(key) }
            }
            else -> {
                if (c.name != r.name) shelves.rename(r.name, c.id)
                if (c.sort_order != r.sort) shelves.setSortOrder(r.sort, c.id)
            }
        }
    }

    private fun applyShelf(key: String, r: ShelfRec) {
        val colKey = key.substringBefore(TAB)
        val novelKey = key.substringAfter(TAB)
        val c = shelves.selectBySyncKey(colKey).executeAsOneOrNull() ?: return
        val b = books.selectBySyncKey(novelKey).executeAsOneOrNull() ?: return
        if (r.member) shelves.add(b.id, c.id) else shelves.removeBook(b.id, c.id)
    }

    /** Apply the stored records about a novel that has just arrived here, or
     *  whose chapters have just been fetched. */
    fun reapplyNovel(b: Book) {
        val key = b.sync_key ?: return
        q.aboutNovel(key).executeAsList().forEach { m -> runCatching { applyAbout(b, m.kind, m.key, m.value_) } }
        q.shelves().executeAsList().filter { it.key.endsWith(TAB + key) }.forEach { m -> runCatching { apply(m.kind, m.key, m.value_) } }
    }

    fun reapplyCollection(key: String) {
        q.shelves().executeAsList().filter { it.key.startsWith(key + TAB) }.forEach { m -> runCatching { apply(m.kind, m.key, m.value_) } }
    }

    // --- sending -----------------------------------------------------------------------

    fun pending(limit: Long): List<SyncChange> =
        q.pending(limit).executeAsList().map { SyncChange(it.kind, it.key, it.value_, it.ts, it.device) }

    fun sent(changes: List<SyncChange>) = changes.forEach { q.sent(it.kind, it.key, it.ts) }

    fun cursor(): Long { device(); return q.state().executeAsOne().cursor }
    fun setCursor(c: Long) = q.setCursor(c)
    fun pendingCount(): Long = q.pendingCount().executeAsOne()
    fun reset() = q.reset()

    companion object {
        /** Enrollment's clock: above the server's seed (1), below any real change. */
        const val ENROLL_TS = 2L
    }
}
