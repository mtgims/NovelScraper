package com.novelscraper.app.data

import kotlinx.serialization.Serializable

// Wire models mirroring backend/app/schemas.py. Unknown/extra JSON fields are
// ignored by the Json config in net/Network.kt, so the backend can add fields
// without breaking the app.

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val invite_code: String? = null,
)

@Serializable
data class UserRead(
    val id: Int,
    val username: String,
    val is_admin: Boolean = false,
    val disabled: Boolean = false,
)

@Serializable
data class AuthConfig(val allow_open_signup: Boolean)

@Serializable
data class VolumeRead(
    val id: Int,
    val number: Int,
    val title: String,
    val chapter_count: Int,
    val size_bytes: Long,
)

@Serializable
data class BookRead(
    val id: Int,
    val slug: String,
    val site: String,
    val title: String,
    val author: String,
    val language: String,
    val has_cover: Boolean = false,
    val rating: Int? = null,
    val can_update: Boolean = false,
    val imported: Boolean = false,
    val collection_ids: List<Int> = emptyList(),
    val volumes: List<VolumeRead> = emptyList(),
)

@Serializable
data class CollectionRead(val id: Int, val name: String, val sort_order: Int = 0)

@Serializable
data class CollectionCreate(val name: String)

@Serializable
data class CollectionUpdate(val name: String? = null, val sort_order: Int? = null)

@Serializable
data class BookReorder(val ordered_ids: List<Int>)

@Serializable
data class BookCollectionsUpdate(val collection_ids: List<Int>)

@Serializable
data class JobCreate(
    val url: String,
    val chapters_per_volume: Int? = null,
    val delay: Float? = null,
    val concurrency: Int? = null,
)

@Serializable
data class JobRead(
    val id: String,
    val site: String,
    val book_slug: String,
    val status: String,
    val phase: String,
    val total_chapters: Int = 0,
    val fetched_chapters: Int = 0,
    val skipped_chapters: Int = 0,
    val error: String? = null,
    val book_id: Int? = null,
)

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
data class ChapterListItem(
    val position: Int,
    val number: String,
    val title: String,
    val volume: Int,
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

@Serializable
data class ProgressUpdate(
    val last_position: Int? = null,
    val scroll: Float? = null,
    val mark_read: Int? = null,
    val unmark_read: Int? = null,
    val mark_positions: List<Int>? = null,
    val unmark_positions: List<Int>? = null,
    val mark_all: Boolean? = null,
    val reset: Boolean? = null,
)

@Serializable
data class ReadingProgressRead(
    val last_position: Int,
    val scroll: Float,
    val read_positions: List<Int>,
    val total_chapters: Int,
    val read_count: Int,
    val chapters_left: Int,
    val percent_read: Float,
    val total_words: Int,
    val words_read: Int,
    val hours_total: Float,
    val hours_left: Float,
)
