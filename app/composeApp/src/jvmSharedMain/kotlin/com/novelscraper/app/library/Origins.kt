package com.novelscraper.app.library

import com.novelscraper.app.data.BookCollectionsUpdate
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.BookUpdate
import com.novelscraper.app.data.ChapterListItem
import com.novelscraper.app.data.CollectionRead
import com.novelscraper.app.data.CollectionUpdate
import com.novelscraper.app.data.ProgressUpdate
import com.novelscraper.app.data.ReadingProgressRead
import com.novelscraper.app.data.SyncRequest
import com.novelscraper.app.data.SyncResponse
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.extensions.SourceNovel
import com.novelscraper.app.net.Net

/** Where source novels come from: the installed extensions. */
interface SourceOrigin {
    /** The source's display name (for a novel's subtitle). */
    suspend fun name(pluginId: String): String
    /** Details and the whole chapter list (every page of a paged list). */
    suspend fun novel(pluginId: String, path: String): SourceNovel
    suspend fun chapter(pluginId: String, path: String): String
    /** The novel's web page. */
    suspend fun webUrl(pluginId: String, path: String): String?
}

/** The NovelScraper server's library, for the account signed in on it. */
interface ServerOrigin {
    /** True while an account is signed in (calls may still fail offline). */
    val enabled: Boolean
    suspend fun books(): List<BookRead>
    suspend fun book(id: Int): BookRead
    suspend fun collections(): List<CollectionRead>
    suspend fun chapters(id: Int): List<ChapterListItem>
    suspend fun chapter(id: Int, position: Int): String
    suspend fun progress(id: Int): ReadingProgressRead
    suspend fun putProgress(id: Int, update: ProgressUpdate)
    suspend fun editBook(id: Int, update: BookUpdate)
    suspend fun setBookCollections(id: Int, update: BookCollectionsUpdate)
    suspend fun updateCollection(id: Int, update: CollectionUpdate)
    suspend fun deleteCollection(id: Int)
    suspend fun deleteBook(id: Int)
    suspend fun sync(request: SyncRequest): SyncResponse
}

/** Paged chapter lists are fetched to the end, up to this many pages. */
private const val MAX_PAGES = 300

object ExtensionSources : SourceOrigin {
    override suspend fun name(pluginId: String): String =
        Extensions.installed.value.firstOrNull { it.id == pluginId }?.name
            ?: Extensions.runtime(pluginId).info.name

    override suspend fun novel(pluginId: String, path: String): SourceNovel {
        val rt = Extensions.runtime(pluginId)
        val novel = rt.novel(path)
        val pages = novel.totalPages ?: 0
        if (pages <= 1 && novel.chapters.isNotEmpty() || !rt.info.hasParsePage) return novel
        // Paged list: the first page may or may not have come with the novel.
        val all = ArrayList(novel.chapters)
        val first = if (novel.chapters.isEmpty()) 1 else 2
        for (p in first..minOf(pages, MAX_PAGES)) all += rt.page(path, p).chapters
        return novel.copy(chapters = all)
    }

    override suspend fun chapter(pluginId: String, path: String): String =
        Extensions.runtime(pluginId).chapter(path)

    override suspend fun webUrl(pluginId: String, path: String): String? {
        val rt = Extensions.runtime(pluginId)
        return if (rt.info.hasResolveUrl) runCatching { rt.resolveUrl(path, isNovel = true) }.getOrNull()
        else rt.info.site.trimEnd('/') + "/" + path.trimStart('/')
    }
}

/** The server through [Net.api]; [enabled] follows the account. */
class NetServer(private val signedIn: () -> Boolean) : ServerOrigin {
    override val enabled: Boolean get() = signedIn()
    override suspend fun books() = Net.api.books()
    override suspend fun book(id: Int) = Net.api.book(id)
    override suspend fun collections() = Net.api.collections()
    override suspend fun chapters(id: Int) = Net.api.chapters(id)
    override suspend fun chapter(id: Int, position: Int) = Net.api.chapter(id, position).content
    override suspend fun progress(id: Int) = Net.api.progress(id)
    override suspend fun putProgress(id: Int, update: ProgressUpdate) { Net.api.putProgress(id, update) }
    override suspend fun editBook(id: Int, update: BookUpdate) { Net.api.editBook(id, update) }
    override suspend fun setBookCollections(id: Int, update: BookCollectionsUpdate) { Net.api.setBookCollections(id, update) }
    override suspend fun updateCollection(id: Int, update: CollectionUpdate) { Net.api.updateCollection(id, update) }
    override suspend fun deleteCollection(id: Int) = Net.api.deleteCollection(id)
    override suspend fun deleteBook(id: Int) = Net.api.deleteBook(id)
    override suspend fun sync(request: SyncRequest) = Net.api.sync(request)
}
