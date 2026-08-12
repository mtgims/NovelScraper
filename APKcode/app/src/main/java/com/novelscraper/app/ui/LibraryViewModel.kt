package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.BookCollectionsUpdate
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.BookReorder
import com.novelscraper.app.data.CollectionCreate
import com.novelscraper.app.data.CollectionRead
import com.novelscraper.app.data.CollectionUpdate
import com.novelscraper.app.net.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LibraryPhase {
    data object Loading : LibraryPhase
    data class Error(val message: String) : LibraryPhase
    data object Ready : LibraryPhase
}

class LibraryViewModel : ViewModel() {

    private val _phase = MutableStateFlow<LibraryPhase>(LibraryPhase.Loading)
    val phase: StateFlow<LibraryPhase> = _phase.asStateFlow()

    private val _books = MutableStateFlow<List<BookRead>>(emptyList())
    val books: StateFlow<List<BookRead>> = _books.asStateFlow()

    private val _collections = MutableStateFlow<List<CollectionRead>>(emptyList())
    val collections: StateFlow<List<CollectionRead>> = _collections.asStateFlow()

    // null tab = "All"; else a collection id.
    private val _tab = MutableStateFlow<Int?>(null)
    val tab: StateFlow<Int?> = _tab.asStateFlow()

    fun load() {
        if (_books.value.isEmpty()) _phase.value = LibraryPhase.Loading
        viewModelScope.launch {
            try {
                _books.value = Net.api.books()
                _collections.value = runCatching { Net.api.collections() }.getOrDefault(emptyList())
                _phase.value = LibraryPhase.Ready
            } catch (e: Exception) {
                if (_books.value.isEmpty())
                    _phase.value = LibraryPhase.Error("Couldn't load your library. Tap to retry.")
            }
        }
    }

    fun selectTab(id: Int?) { _tab.value = id }

    fun booksForTab(): List<BookRead> {
        val t = _tab.value ?: return _books.value
        return _books.value.filter { t in it.collection_ids }
    }

    /** Reorder is only offered on the "All" tab, so indices map to the full list. */
    fun moveBook(from: Int, to: Int) {
        val list = _books.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _books.value = list
    }

    fun commitOrder() {
        val ids = _books.value.map { it.id }
        viewModelScope.launch { runCatching { Net.api.reorderBooks(BookReorder(ids)) } }
    }

    fun createCollection(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { Net.api.createCollection(CollectionCreate(name.trim())) }
                .onSuccess { _collections.value = _collections.value + it }
        }
    }

    fun renameCollection(id: Int, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { Net.api.updateCollection(id, CollectionUpdate(name = name.trim())) }
                .onSuccess { upd -> _collections.value = _collections.value.map { if (it.id == id) upd else it } }
        }
    }

    fun deleteCollection(id: Int) {
        viewModelScope.launch {
            runCatching { Net.api.deleteCollection(id) }.onSuccess {
                _collections.value = _collections.value.filter { it.id != id }
                if (_tab.value == id) _tab.value = null
                // books keep existing; drop the id from local copies
                _books.value = _books.value.map { b ->
                    if (id in b.collection_ids) b.copy(collection_ids = b.collection_ids - id) else b
                }
            }
        }
    }

    fun setBookCollections(bookId: Int, ids: List<Int>) {
        viewModelScope.launch {
            runCatching { Net.api.setBookCollections(bookId, BookCollectionsUpdate(ids)) }
                .onSuccess { updated -> _books.value = _books.value.map { if (it.id == bookId) updated else it } }
        }
    }
}
