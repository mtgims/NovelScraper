package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.ChapterRead
import com.novelscraper.app.data.ProgressUpdate
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

class ReaderViewModel : ViewModel() {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var loadingKey: Pair<Int, Int>? = null

    fun load(bookId: Int, position: Int) {
        val key = bookId to position
        if (loadingKey == key && _state.value is ReaderState.Data) return
        loadingKey = key
        _state.value = ReaderState.Loading
        viewModelScope.launch {
            _state.value = try {
                val chapter = Net.api.chapter(bookId, position)
                // Mark read + set resume point (best-effort).
                markRead(bookId, position)
                ReaderState.Data(chapter)
            } catch (e: Exception) {
                ReaderState.Error("Couldn't load this chapter.")
            }
        }
    }

    private fun markRead(bookId: Int, position: Int) {
        viewModelScope.launch {
            try {
                Net.api.putProgress(bookId, ProgressUpdate(last_position = position, mark_read = position))
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
