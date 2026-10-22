package com.novelscraper.app.data

import kotlinx.serialization.Serializable

// Plain models the screens and the library share. Their names follow the old
// server's JSON, which is why some fields are snake_case.

@Serializable
data class BookStat(
    val book_id: Int,
    val title: String,
    val total_chapters: Int,
    val read_count: Int,
    val percent_read: Float,
)

@Serializable
data class StatsRead(
    val total_books: Int,
    val books_started: Int,
    val books_finished: Int,
    val total_chapters: Int,
    val chapters_read: Int,
    val total_words: Int,
    val words_read: Int,
    val hours_read: Float,
    val hours_total: Float,
    val hours_remaining: Float,
    val percent_read: Float,
    val books: List<BookStat> = emptyList(),
)

@Serializable
data class ChapterRead(
    val position: Int,
    val number: String,
    val title: String,
    val content: String,
    val has_prev: Boolean,
    val has_next: Boolean,
)

// One sync record (library/LibrarySync.kt), as same-Wi-Fi and folder sync carry it.

@Serializable
data class SyncChange(
    val kind: String,
    val key: String,
    val value: String,
    val ts: Long,
    val device: String = "",
)
