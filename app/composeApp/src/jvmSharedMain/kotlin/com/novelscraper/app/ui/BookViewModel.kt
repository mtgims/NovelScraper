package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.BookCollectionsUpdate
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.BookUpdate
import com.novelscraper.app.data.ChapterListItem
import com.novelscraper.app.data.CollectionRead
import com.novelscraper.app.data.ProgressUpdate
import com.novelscraper.app.data.ReadingProgressRead
import com.novelscraper.app.net.Net
import com.novelscraper.app.net.ScrapeRelay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.novelscraper.app.net.detail
import retrofit2.HttpException

sealed interface BookState {
    data object Loading : BookState
    data class Error(val message: String) : BookState
    data class Data(
        val book: BookRead,
        val chapters: List<ChapterListItem>,
        val progress: ReadingProgressRead?,
    ) : BookState
}

class BookViewModel : ViewModel() {

    private val _state = MutableStateFlow<BookState>(BookState.Loading)
    val state: StateFlow<BookState> = _state.asStateFlow()

    private val _collections = MutableStateFlow<List<CollectionRead>>(emptyList())
    val collections: StateFlow<List<CollectionRead>> = _collections.asStateFlow()

    // One-shot user message for delete/update actions (shown as a toast, then cleared).
    private val _action = MutableStateFlow<String?>(null)
    val action: StateFlow<String?> = _action.asStateFlow()
    fun clearAction() { _action.value = null }

    /** Delete this novel, then invoke [onDeleted] (navigate back) on success. */
    fun deleteBook(bookId: Int, onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                Net.api.deleteBook(bookId)
                onDeleted()
            } catch (e: Exception) {
                _action.value = "Couldn't delete this novel."
            }
        }
    }

    /** Kick off an incremental re-scrape that pulls in new chapters (continuing
     *  the last volume). Routes through the phone relay for gated sites. */
    fun checkForNewChapters(bookId: Int) {
        viewModelScope.launch {
            ScrapeRelay.start()  // best-effort: gated updates should use the phone IP
            _action.value = try {
                Net.api.updateBook(bookId)
                "Checking for new chapters… they'll be added to the last volume."
            } catch (e: HttpException) {
                e.detail() ?: when (e.code()) {
                    409 -> "An update is already running."
                    400 -> "This novel has no source to update from (imported?)."
                    else -> "Couldn't start the update (${e.code()})."
                }
            } catch (e: Exception) {
                "Can't reach the server."
            }
        }
    }

    private var loadedId: Int? = null

    /** Load once per book id (safe to call on every recomposition). */
    fun ensureLoaded(bookId: Int) {
        if (loadedId == bookId) return
        loadedId = bookId
        load(bookId)
    }

    /** Re-fetch just the progress (read count / resume point) without a full
     *  reload — called when returning from the reader so the detail reflects
     *  chapters just read. Keeps the current data visible (no Loading flash). */
    fun refreshProgress(bookId: Int) {
        val cur = _state.value
        if (cur !is BookState.Data || cur.book.id != bookId) return
        viewModelScope.launch {
            try {
                val p = Net.api.progress(bookId)
                val now = _state.value
                if (now is BookState.Data && now.book.id == bookId) {
                    _state.value = now.copy(progress = p)
                }
            } catch (_: Exception) { /* keep showing current */ }
        }
    }

    fun load(bookId: Int) {
        _state.value = BookState.Loading
        viewModelScope.launch {
            try {
                // Fetch book + chapters in parallel and render as soon as both
                // return — don't block the screen on /progress (its server-side
                // word-count backfill can take seconds on large books).
                val bookD = async { Net.api.book(bookId) }
                val chaptersD = async { Net.api.chapters(bookId) }
                val book = bookD.await()
                val chapters = chaptersD.await()
                _state.value = BookState.Data(book, chapters, progress = null)

                // Fill in progress + collections in the background.
                launch {
                    val p = runCatching { Net.api.progress(bookId) }.getOrNull()
                    val now = _state.value
                    if (p != null && now is BookState.Data && now.book.id == bookId) {
                        _state.value = now.copy(progress = p)
                    }
                }
                launch {
                    _collections.value = runCatching { Net.api.collections() }.getOrDefault(emptyList())
                }
            } catch (e: Exception) {
                _state.value = BookState.Error("Couldn't load this book.")
            }
        }
    }

    // --- reading progress edits ---------------------------------------
    // All fold the returned ReadingProgressRead back into state so the TOC
    // check marks + header progress update immediately.

    fun setChapterRead(bookId: Int, position: Int, read: Boolean) = applyProgress(
        bookId,
        if (read) ProgressUpdate(mark_read = position) else ProgressUpdate(unmark_read = position),
    )

    fun setPositionsRead(bookId: Int, positions: List<Int>, read: Boolean) {
        if (positions.isEmpty()) return
        applyProgress(
            bookId,
            if (read) ProgressUpdate(mark_positions = positions)
            else ProgressUpdate(unmark_positions = positions),
        )
    }

    fun markAllRead(bookId: Int) = applyProgress(bookId, ProgressUpdate(mark_all = true))
    fun resetProgress(bookId: Int) = applyProgress(bookId, ProgressUpdate(reset = true))

    private fun applyProgress(bookId: Int, update: ProgressUpdate) {
        viewModelScope.launch {
            try {
                val p = Net.api.putProgress(bookId, update)
                val now = _state.value
                if (now is BookState.Data && now.book.id == bookId) {
                    _state.value = now.copy(progress = p)
                }
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /** Set the book's 1-5 star rating, or clear it with 0. Shown immediately, then
     *  saved; if the save fails the previous rating comes back. */
    fun setRating(rating: Int) {
        val cur = _state.value as? BookState.Data ?: return
        val previous = cur.book.rating
        _state.value = cur.copy(book = cur.book.copy(rating = rating.takeIf { it > 0 }))
        viewModelScope.launch {
            try {
                val updated = Net.api.editBook(cur.book.id, BookUpdate(rating = rating))
                val now = _state.value
                if (now is BookState.Data && now.book.id == updated.id) {
                    _state.value = now.copy(book = updated)
                }
            } catch (e: Exception) {
                val now = _state.value
                if (now is BookState.Data && now.book.id == cur.book.id) {
                    _state.value = now.copy(book = now.book.copy(rating = previous))
                }
                _action.value = "Couldn't save the rating."
            }
        }
    }

    /** Add/remove this book from a collection (optimistic + PUT). */
    fun toggleCollection(collectionId: Int) {
        val cur = _state.value as? BookState.Data ?: return
        val ids = cur.book.collection_ids.toMutableList()
        if (collectionId in ids) ids.remove(collectionId) else ids.add(collectionId)
        _state.value = cur.copy(book = cur.book.copy(collection_ids = ids))
        viewModelScope.launch {
            runCatching { Net.api.setBookCollections(cur.book.id, BookCollectionsUpdate(ids)) }
                .onSuccess { updated ->
                    val now = _state.value
                    if (now is BookState.Data && now.book.id == updated.id) {
                        _state.value = now.copy(book = updated)
                    }
                }
        }
    }
}
