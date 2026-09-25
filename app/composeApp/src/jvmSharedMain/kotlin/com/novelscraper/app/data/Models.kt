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

/** Re-entering the password for something irreversible. */
@Serializable
data class PasswordConfirm(val password: String)

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

// Library sync (POST /api/sync, backend app/services/sync.py).

@Serializable
data class SyncChange(
    val kind: String,
    val key: String,
    val value: String,
    val ts: Long,
    val device: String = "",
)

@Serializable
data class SyncRequest(val cursor: Long, val device: String, val changes: List<SyncChange>)

@Serializable
data class SyncResponse(val cursor: Long, val changes: List<SyncChange> = emptyList(), val more: Boolean = false)
