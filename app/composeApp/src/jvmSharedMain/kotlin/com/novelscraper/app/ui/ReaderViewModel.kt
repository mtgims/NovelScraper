package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.ChapterListItem
import com.novelscraper.app.data.ChapterRead
import com.novelscraper.app.data.ProgressUpdate
import com.novelscraper.app.data.ReadingProgressRead
import com.novelscraper.app.net.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ReaderState {
    data object Loading : ReaderState
    data class Error(val message: String) : ReaderState
    data class Data(val chapter: ChapterRead) : ReaderState
}

/** Book + chapter list + progress, fetched once so the reader's chapters sheet
 *  can list every volume and reflect/edit read state. */
data class ReaderMeta(
    val book: BookRead,
    val chapters: List<ChapterListItem>,
    val readPositions: Set<Int>,
)

class ReaderViewModel : ViewModel() {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val _meta = MutableStateFlow<ReaderMeta?>(null)
    val meta: StateFlow<ReaderMeta?> = _meta.asStateFlow()

    private var loadingKey: Pair<Int, Int>? = null
    private var metaId: Int? = null

    fun load(bookId: Int, position: Int) {
        val key = bookId to position
        if (loadingKey == key && _state.value is ReaderState.Data) return
        loadingKey = key
        _state.value = ReaderState.Loading
        viewModelScope.launch {
            _state.value = try {
                val chapter = Net.api.chapter(bookId, position)
                // Mark read + set resume point (best-effort), and reflect it locally.
                markRead(bookId, position)
                ReaderState.Data(chapter)
            } catch (e: Exception) {
                ReaderState.Error("Couldn't load this chapter.")
            }
        }
    }

    /** Load book + chapter list + progress once for the chapters sheet. */
    fun ensureMeta(bookId: Int) {
        if (metaId == bookId) return
        metaId = bookId
        viewModelScope.launch {
            try {
                val book = Net.api.book(bookId)
                val chapters = Net.api.chapters(bookId)
                val progress = runCatching { Net.api.progress(bookId) }.getOrNull()
                _meta.value = ReaderMeta(book, chapters, progress?.read_positions?.toSet() ?: emptySet())
            } catch (_: Exception) { metaId = null /* allow retry */ }
        }
    }

    private fun markRead(bookId: Int, position: Int) {
        applyProgress(bookId, ProgressUpdate(last_position = position, mark_read = position))
    }

    /** Toggle one chapter's read state from the sheet. */
    fun setChapterRead(bookId: Int, position: Int, read: Boolean) {
        applyProgress(
            bookId,
            if (read) ProgressUpdate(mark_read = position)
            else ProgressUpdate(unmark_read = position),
        )
    }

    /** PUT the update and fold the returned read set back into [meta]. */
    private fun applyProgress(bookId: Int, update: ProgressUpdate) {
        viewModelScope.launch {
            try {
                val p = Net.api.putProgress(bookId, update)
                val m = _meta.value
                if (m != null && m.book.id == bookId) {
                    _meta.value = m.copy(readPositions = p.read_positions.toSet())
                }
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /** Persist in-chapter scroll fraction to the server (resume across devices). */
    fun saveScroll(bookId: Int, position: Int, fraction: Float) {
        viewModelScope.launch {
            try {
                Net.api.putProgress(bookId, ProgressUpdate(last_position = position, scroll = fraction))
            } catch (_: Exception) { /* best-effort */ }
        }
    }
}
