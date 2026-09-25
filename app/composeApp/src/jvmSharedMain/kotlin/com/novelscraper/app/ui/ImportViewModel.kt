package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.epub.Epub
import com.novelscraper.app.library.Library
import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ImportUi {
    data object Idle : ImportUi
    data object Importing : ImportUi
    data class Error(val message: String) : ImportUi
    data class Done(val title: String, val count: Int) : ImportUi
}

/**
 * Importing EPUBs, which happens here on the device: the file is read, its
 * chapters are stored, and the novel joins the library. It needs no account and
 * nothing leaves the device, which is also why an imported novel does not sync
 * (see [com.novelscraper.app.library.LibraryStore.importEpub]).
 */
class ImportViewModel : ViewModel() {
    private val _ui = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val ui: StateFlow<ImportUi> = _ui.asStateFlow()

    fun importEpubs(files: List<PickedFile>) {
        if (files.isEmpty() || _ui.value is ImportUi.Importing) return
        _ui.value = ImportUi.Importing
        viewModelScope.launch(Dispatchers.IO) {
            var last: String? = null
            var done = 0
            var failed: String? = null
            for (file in files) {
                try {
                    val parsed = file.open()?.use { Epub.read(it) } ?: error("could not be opened")
                    Library.store.importEpub(parsed)
                    last = parsed.title
                    done++
                } catch (e: Epub.Invalid) {
                    failed = "${file.name}: ${e.message}."
                    Log.w(TAG, "import ${file.name}: ${e.message}")
                } catch (e: Exception) {
                    failed = "${file.name} could not be imported."
                    Log.w(TAG, "import ${file.name}: ${e.message}")
                }
            }
            // Several files at once: one bad EPUB does not lose the good ones.
            _ui.value = when {
                done > 0 -> ImportUi.Done(last.orEmpty(), done)
                else -> ImportUi.Error(failed ?: "Nothing could be imported.")
            }
        }
    }

    fun reset() { _ui.value = ImportUi.Idle }

    private companion object { const val TAG = "Import" }
}
