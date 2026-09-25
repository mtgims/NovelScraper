package com.novelscraper.app.library

import com.novelscraper.app.data.SyncRequest
import com.novelscraper.app.data.SyncResponse
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.extensions.SourceNovel
import com.novelscraper.app.net.Net

/** Where novels come from: the installed extensions. */
interface SourceOrigin {
    /** The source's display name (for a novel's subtitle). */
    suspend fun name(pluginId: String): String
    /** Details and the whole chapter list (every page of a paged list). */
    suspend fun novel(pluginId: String, path: String): SourceNovel
    suspend fun chapter(pluginId: String, path: String): String
    /** The novel's web page. */
    suspend fun webUrl(pluginId: String, path: String): String?
}

/** The account's sync endpoint. The server holds no novels, only the records
 *  that keep a library in step across devices. */
interface ServerOrigin {
    /** True while an account is signed in (calls may still fail offline). */
    val enabled: Boolean
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
    override suspend fun sync(request: SyncRequest) = Net.api.sync(request)
}
