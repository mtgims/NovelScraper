package com.novelscraper.app.library

import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.extensions.SourceNovel

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
