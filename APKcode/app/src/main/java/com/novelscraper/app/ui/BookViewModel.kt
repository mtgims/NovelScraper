package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.ChapterListItem
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

    private var loadedId: Int? = null

    /** Load once per book id (safe to call on every recomposition). */
    fun ensureLoaded(bookId: Int) {
        if (loadedId == bookId) return
        loadedId = bookId
        load(bookId)
    }

    fun load(bookId: Int) {
        _state.value = BookState.Loading
        viewModelScope.launch {
            _state.value = try {
                val book = Net.api.book(bookId)
                val chapters = Net.api.chapters(bookId)
                // Progress is best-effort: a never-opened book may have none.
                val progress = try { Net.api.progress(bookId) } catch (e: Exception) { null }
                BookState.Data(book, chapters, progress)
            } catch (e: Exception) {
                BookState.Error("Couldn't load this book.")
            }
        }
    }
}
