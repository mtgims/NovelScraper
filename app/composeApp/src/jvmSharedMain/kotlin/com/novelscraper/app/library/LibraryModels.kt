package com.novelscraper.app.library


/** A novel in the local library (or opened from Browse and not added yet). */
data class LibBook(
    val id: Int,
    val title: String,
    val author: String,
    /** Cover image URL, or null for none. */
    val cover: String?,
    /** The source's name, or "Imported" for a novel read out of an EPUB. */
    val site: String,
    val rating: Int?,
    val inLibrary: Boolean,
    /** Source novels: the extension and the novel's path in it. */
    val pluginId: String?,
    val path: String?,
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
    /** When a chapter of it was last opened, or the novel first opened (0 = never). */
    val openedAt: Long = 0,
) {
    val isSource: Boolean get() = pluginId != null
    /** Read out of an EPUB on this device: no source to fetch from. */
    val isImported: Boolean get() = pluginId == null
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

data class LibCollection(val id: Int, val name: String, val sortOrder: Int)

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

/** What moving a novel to another source would carry over ([LibraryStore.migrationPreview]). */
data class MigrationPreview(
    val chaptersHere: Int,
    val chaptersThere: Int,
    val readHere: Int,
    val readThere: Int,
    /** The chapter reading left off at, and the one it becomes; null when nothing was opened. */
    val resumeHere: String?,
    val resumeThere: String?,
    /** The place was found as the same chapter, not guessed. */
    val exact: Boolean,
    /** Chapters were matched by their numbers (else by their order). */
    val byNumber: Boolean,
    /** The reader is further on than the new source goes. */
    val pastTheEnd: Boolean,
    val downloadedHere: Int,
    val siteHere: String,
)
