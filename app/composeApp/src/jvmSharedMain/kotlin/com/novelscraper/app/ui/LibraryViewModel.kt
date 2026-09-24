package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.library.LibBook
import com.novelscraper.app.library.LibCollection
import com.novelscraper.app.library.ChapterDownloads
import com.novelscraper.app.library.Library
import com.novelscraper.app.library.LibrarySyncRunner
import com.novelscraper.app.library.LibraryUpdates
import com.novelscraper.app.net.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The library grid, straight from the local library; [refresh] imports what is
 *  new on the server (when signed in). */
class LibraryViewModel : ViewModel() {
    private val lib = Library.store

    /** Null until the database has answered once (so an empty library doesn't
     *  flash "empty" on start). */
    val books: StateFlow<List<LibBook>?> =
        lib.libraryFlow().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val collections: StateFlow<List<LibCollection>> =
        lib.collectionsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // null tab = "All"; else a collection id.
    private val _tab = MutableStateFlow<Int?>(null)
    val tab: StateFlow<Int?> = _tab.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** While dragging: the order being shown, before it is saved. */
    private val _dragOrder = MutableStateFlow<List<Int>?>(null)
    val dragOrder: StateFlow<List<Int>?> = _dragOrder.asStateFlow()

    /** What a check for new chapters is doing. */
    val updates = LibraryUpdates.state

    /** Pull to refresh: the server's library, sync, and a look for new chapters. */
    fun refresh(force: Boolean = true) {
        LibraryUpdates.checkAll(force = force, announce = force)
        if (_refreshing.value || !Account.signedIn) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                lib.pullServer()
                LibrarySyncRunner.now()
            } catch (_: Exception) {
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun selectTab(id: Int?) { _tab.value = id }

    /** Reorder is only offered on the "All" tab, so indices map to the full list. */
    fun moveBook(from: Int, to: Int) {
        val list = (_dragOrder.value ?: books.value?.map { it.id } ?: return).toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _dragOrder.value = list
    }

    fun commitOrder() {
        val ids = _dragOrder.value ?: return
        viewModelScope.launch {
            lib.reorder(ids)
            _dragOrder.value = null
        }
    }

    // --- what a right-click offers -------------------------------------------

    fun markAllRead(id: Int) { viewModelScope.launch { lib.markAllRead(id) } }

    /** Keep the whole novel on this device, from the library, without opening it. */
    fun downloadAll(id: Int) = ChapterDownloads.download(id, null)

    fun removeFromLibrary(id: Int) {
        viewModelScope.launch {
            lib.setInLibrary(id, false)
            lib.removeDownloads(id)
        }
    }

    fun removeDownloads(id: Int) { viewModelScope.launch { lib.removeDownloads(id) } }

    fun createCollection(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { lib.createCollection(name) }
    }

    fun renameCollection(id: Int, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { lib.renameCollection(id, name) }
    }

    fun deleteCollection(id: Int) {
        if (_tab.value == id) _tab.value = null
        viewModelScope.launch { lib.deleteCollection(id) }
    }
}
