package com.novelscraper.app.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.net.Net
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
import org.json.JSONObject
import retrofit2.HttpException

sealed interface ImportUi {
    data object Idle : ImportUi
    data object Uploading : ImportUi
    data class Error(val message: String) : ImportUi
    data class Done(val book: BookRead) : ImportUi
}

class ImportViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val ui: StateFlow<ImportUi> = _ui.asStateFlow()

    fun importEpubs(uris: List<Uri>) {
        if (uris.isEmpty() || _ui.value is ImportUi.Uploading) return
        _ui.value = ImportUi.Uploading
        val cr = getApplication<Application>().contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = try {
                val parts = uris.map { part(cr, it) }
                ImportUi.Done(Net.api.importEpubs(parts))
            } catch (e: HttpException) {
                ImportUi.Error(detailOf(e) ?: "Import failed (${e.code()}).")
            } catch (e: Exception) {
                ImportUi.Error("Import failed. Make sure the files are valid EPUBs.")
            }
        }
    }

    fun reset() { _ui.value = ImportUi.Idle }

    private fun part(cr: ContentResolver, uri: Uri): MultipartBody.Part {
        val name = displayName(cr, uri) ?: "book.epub"
        val body = object : RequestBody() {
            override fun contentType() = "application/epub+zip".toMediaTypeOrNull()
            override fun writeTo(sink: BufferedSink) {
                cr.openInputStream(uri)?.use { input -> sink.writeAll(input.source()) }
            }
        }
        return MultipartBody.Part.createFormData("files", name, body)
    }

    private fun displayName(cr: ContentResolver, uri: Uri): String? =
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun detailOf(e: HttpException): String? = try {
        e.response()?.errorBody()?.string()?.let { JSONObject(it).optString("detail").ifBlank { null } }
    } catch (_: Exception) { null }
}
