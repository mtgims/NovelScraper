package com.novelscraper.app.library

import com.novelscraper.app.data.BookRead

/** A novel in the local library (or opened from Browse and not added yet). */
data class LibBook(
    val id: Int,
    val title: String,
    val author: String,
    /** Cover image URL, or null for none. */
    val cover: String?,
    /** The source's name, or the server's site. */
    val site: String,
    val rating: Int?,
    val inLibrary: Boolean,
    /** Source novels: the extension and the novel's path in it. */
    val pluginId: String?,
    val path: String?,
    /** Server novels: the id there, and the server's last description of it. */
    val serverId: Int?,
    val server: BookRead?,
    val summary: String = "",
    val genres: String = "",
    val status: String = "",
    val webUrl: String? = null,
    val collectionIds: List<Int> = emptyList(),
    val chapterCount: Int = 0,
    val unreadCount: Int = 0,
    val downloadedCount: Int = 0,
    /** When the chapter list was last fetched (0 = never). */
    val checkedAt: Long = 0,
) {
    val isServer: Boolean get() = serverId != null
    val isSource: Boolean get() = pluginId != null
}

data class LibChapter(
    val position: Int,
    val number: String,
    val title: String,
    val volume: Int,
    val read: Boolean,
    val downloaded: Boolean,
    val releaseTime: String? = null,
)

data class LibCollection(val id: Int, val name: String, val sortOrder: Int, val serverId: Int?)

/** Where a novel's reader is: the resume point and the chapters marked read. */
data class LibProgress(
    /** The chapter to continue from, or null if none was opened yet. */
    val lastPosition: Int?,
    /** How far down that chapter (0..1). */
    val scroll: Float,
    val readPositions: Set<Int>,
    val total: Int,
    /** The sentence the reader or narration was at in that chapter, if known. */
    val sentence: Int? = null,
) {
    val readCount: Int get() = readPositions.size
    val percent: Float get() = if (total == 0) 0f else readCount * 100f / total
}
