package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.net.Net
import com.novelscraper.app.platform.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import com.novelscraper.app.net.detail
import retrofit2.HttpException

sealed interface ImportUi {
    data object Idle : ImportUi
    data object Uploading : ImportUi
    data class Error(val message: String) : ImportUi
    data class Done(val book: BookRead) : ImportUi
}

class ImportViewModel : ViewModel() {
    private val _ui = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val ui: StateFlow<ImportUi> = _ui.asStateFlow()

    fun importEpubs(files: List<PickedFile>) {
        if (files.isEmpty() || _ui.value is ImportUi.Uploading) return
        _ui.value = ImportUi.Uploading
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = try {
                val parts = files.map { part(it) }
                ImportUi.Done(Net.api.importEpubs(parts))
            } catch (e: HttpException) {
                ImportUi.Error(e.detail() ?: "Import failed (${e.code()}).")
            } catch (e: Exception) {
                ImportUi.Error("Import failed. Make sure the files are valid EPUBs.")
            }
        }
    }

    fun reset() { _ui.value = ImportUi.Idle }

    private fun part(file: PickedFile): MultipartBody.Part {
        val body = object : RequestBody() {
            override fun contentType() = "application/epub+zip".toMediaTypeOrNull()
            override fun writeTo(sink: BufferedSink) {
                file.open()?.use { input -> sink.writeAll(input.source()) }
            }
        }
        return MultipartBody.Part.createFormData("files", file.name, body)
    }
}
