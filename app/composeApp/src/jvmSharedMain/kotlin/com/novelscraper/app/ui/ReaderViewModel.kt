package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.ChapterRead
import com.novelscraper.app.library.LibBook
import com.novelscraper.app.library.LibChapter
import com.novelscraper.app.library.Library
import com.novelscraper.app.ui.browse.describe
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface ReaderState {
    data object Loading : ReaderState
    data class Error(val message: String) : ReaderState
    /** [scrollKey] names the novel for the per-chapter scroll positions;
     *  [anchor] is the sentence to open at, when this chapter is the novel's
     *  resume point (set here or on another device). */
    data class Data(val chapter: ChapterRead, val scrollKey: String, val anchor: Int? = null) : ReaderState
}

/** The novel and its chapter list, for the reader's chapters sheet. */
data class ReaderMeta(
    val book: LibBook,
    val chapters: List<LibChapter>,
    val readPositions: Set<Int>,
)

/** A chapter from the local library: stored text, or fetched from the novel's
 *  source or server (and cached). Opening it makes it the resume point. */
class ReaderViewModel : ViewModel() {
    private val lib = Library.store

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val _meta = MutableStateFlow<ReaderMeta?>(null)
    val meta: StateFlow<ReaderMeta?> = _meta.asStateFlow()

    private var loadingKey: Pair<Int, Int>? = null
    private var loadJob: Job? = null
    private var metaId: Int? = null

    fun load(bookId: Int, position: Int) {
        val key = bookId to position
        if (loadingKey == key && _state.value is ReaderState.Data) return
        loadingKey = key
        _state.value = ReaderState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = try {
                val book = lib.book(bookId) ?: error("This novel is no longer in your library.")
                val chapter = lib.chapter(bookId, position)
                val resume = lib.progress(bookId)
                val anchor = resume.sentence?.takeIf { resume.lastPosition == position }
                lib.markOpened(bookId, position)
                // Fetch the next chapter ahead, so turning the page (or narration
                // rolling on) doesn't wait on the site.
                if (chapter.has_next) launch { runCatching { lib.chapter(bookId, position + 1) } }
                ReaderState.Data(chapter, scrollKey(book), anchor)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                ReaderState.Error(e.message ?: "Couldn't load this chapter.")
            } catch (e: Exception) {
                ReaderState.Error(describe(e))
            }
        }
    }

    /** Follow the novel and its chapter list for the chapters sheet. */
    fun ensureMeta(bookId: Int) {
        if (metaId == bookId) return
        metaId = bookId
        viewModelScope.launch {
            combine(lib.bookFlow(bookId), lib.chaptersFlow(bookId)) { b, list ->
                b?.let { ReaderMeta(it, list, list.filter { c -> c.read }.map { c -> c.position }.toSet()) }
            }.collect { _meta.value = it }
        }
    }

    fun setChapterRead(bookId: Int, position: Int, read: Boolean) {
        viewModelScope.launch { lib.setRead(bookId, listOf(position), read) }
    }

    /** Save the resume point's scroll (how far down this chapter). */
    fun saveScroll(bookId: Int, position: Int, fraction: Float, sentence: Int?) {
        // Outlives the screen: this runs as the reader closes.
        lib.scope.launch { runCatching { lib.saveProgress(bookId, position, fraction, sentence) } }
    }

    companion object {
        /** Server novels keep the server's id, as scroll positions saved before
         *  the local library were keyed by it. */
        fun scrollKey(book: LibBook): String = "L${book.id}"
    }
}
