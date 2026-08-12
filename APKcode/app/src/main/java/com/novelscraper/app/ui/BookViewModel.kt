package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.BookCollectionsUpdate
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.ChapterListItem
import com.novelscraper.app.data.CollectionRead
import com.novelscraper.app.data.ReadingProgressRead
import com.novelscraper.app.net.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
            _state.value = try {
                val book = Net.api.book(bookId)
                val chapters = Net.api.chapters(bookId)
                // Progress is best-effort: a never-opened book may have none.
                val progress = try { Net.api.progress(bookId) } catch (e: Exception) { null }
                _collections.value = runCatching { Net.api.collections() }.getOrDefault(emptyList())
                BookState.Data(book, chapters, progress)
            } catch (e: Exception) {
                BookState.Error("Couldn't load this book.")
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
