package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.net.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LibraryState {
    data object Loading : LibraryState
    data class Error(val message: String) : LibraryState
    data class Data(val books: List<BookRead>) : LibraryState
}

class LibraryViewModel : ViewModel() {

    private val _state = MutableStateFlow<LibraryState>(LibraryState.Loading)
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init { load() }

    fun load() {
        _state.value = LibraryState.Loading
        viewModelScope.launch {
            _state.value = try {
                LibraryState.Data(Net.api.books())
            } catch (e: Exception) {
                LibraryState.Error("Couldn't load your library. Pull to retry.")
            }
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                _state.value = LibraryState.Data(Net.api.books())
            } catch (e: Exception) {
                // keep whatever is showing; surface error only if we had nothing
                if (_state.value !is LibraryState.Data) {
                    _state.value = LibraryState.Error("Couldn't load your library. Pull to retry.")
                }
            } finally {
                _refreshing.value = false
            }
        }
    }
}
