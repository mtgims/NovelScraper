package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.library.ChapterDownloads
import com.novelscraper.app.library.LibBook
import com.novelscraper.app.library.LibChapter
import com.novelscraper.app.library.LibCollection
import com.novelscraper.app.library.LibProgress
import com.novelscraper.app.library.Library
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.extensions.PluginNotInstalledException
import com.novelscraper.app.tts.AudiobookExport
import com.novelscraper.app.epub.Epub
import com.novelscraper.app.net.epubFileName
import com.novelscraper.app.net.imageClient
import com.novelscraper.app.net.zipFileName
import com.novelscraper.app.platform.saveToDownloads
import com.novelscraper.app.ui.browse.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which chapters "Download" takes. */
enum class DownloadChoice { All, Unread, Next10 }

/** Which chapters "Save as audio" reads out. */
enum class AudioRange { ThisChapter, Next10, All }

/** A novel's page: details, chapters and progress from the local library, kept
 *  up to date from its source. */
class BookViewModel(private val bookId: Int) : ViewModel() {
    private val lib = Library.store

    /** Null while loading, or if the novel is gone. */
    val book: StateFlow<LibBook?> = lib.bookFlow(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val chapters: StateFlow<List<LibChapter>?> = lib.chaptersFlow(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val progress: StateFlow<LibProgress?> = lib.progressFlow(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val collections: StateFlow<List<LibCollection>> = lib.collectionsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val queued: StateFlow<Long> = lib.queuedForBookFlow(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    /** Chapters of this novel whose download gave up. */
    val failedDownloads: StateFlow<Long> = lib.failedForBookFlow(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val downloads = ChapterDownloads.state

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Why the chapter list couldn't be fetched (shown while there is none). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // One-shot user message (shown as a toast, then cleared).
    private val _action = MutableStateFlow<String?>(null)
    val action: StateFlow<String?> = _action.asStateFlow()
    fun clearAction() { _action.value = null }

    init {
        viewModelScope.launch {
            val b = lib.book(bookId) ?: return@launch
            // A source novel is fetched the first time it is opened.
            if (b.checkedAt == 0L) refresh()
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                lib.refresh(bookId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: PluginNotInstalledException) {
                // Name the source as this novel knows it ("Royal Road", not its plugin id).
                val name = (book.value ?: lib.book(bookId))?.site?.takeIf { it.isNotBlank() }
                _error.value = if (name != null) "The $name source isn't installed on this device. " +
                    "Install it under Browse, Extensions." else describe(e)
            } catch (e: Exception) {
                _error.value = describe(e)
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Source novels: fetch the chapter list again. */
    fun checkForNewChapters() {
        val b = book.value ?: return
        if (b.isSource) {
            val before = chapters.value?.size ?: 0
            viewModelScope.launch {
                _refreshing.value = true
                _action.value = try {
                    lib.refresh(bookId)
                    val added = lib.progress(bookId).total - before
                    if (added > 0) "$added new chapter${if (added == 1) "" else "s"}." else "No new chapters."
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    describe(e)
                } finally {
                    _refreshing.value = false
                }
            }
            return
        }
        // Anything without a source (an imported EPUB) has nothing to check.
        _action.value = "This novel was imported, so there is nothing to check."
    }

    /** Write the novel to an EPUB in the user's Downloads, from the chapters
     *  held on the device, so it works offline. */
    fun saveEpub(title: String, author: String) {
        viewModelScope.launch {
            val positions = lib.storedPositions(bookId)
            val chapterCount = positions.size
            val volumes = positions.map { lib.volumeOf(it) }.distinct()
            if (volumes.isEmpty()) {
                _action.value = "No chapters are on this device yet. Download some first."
                return@launch
            }
            val b = book.value
            val language = Epub.languageCode(
                b?.pluginId?.let { id -> Extensions.installed.value.firstOrNull { it.id == id }?.lang })
            val cover = b?.let { coverForExport(it.cover, it.pluginId) }
            // One volume is a single EPUB; several travel as a zip of them.
            // Each volume's text is read as it is written, not all at once.
            val saved = runCatching {
                if (volumes.size == 1) {
                    saveToDownloads(epubFileName(title), "application/epub+zip") { out ->
                        Epub.write(title, author, lib.storedVolume(bookId, volumes.single()).chapters, out, language, cover)
                    }
                } else {
                    saveToDownloads(zipFileName(title), "application/zip") { out ->
                        Epub.writeZip(title, title, author, volumes, { lib.storedVolume(bookId, it) }, out, language, cover)
                    }
                }
            }.getOrNull()
            _action.value = when {
                saved == null -> "Couldn't save the EPUB."
                volumes.size == 1 -> "Saved $saved ($chapterCount chapters) to Downloads."
                else -> "Saved $saved (${volumes.size} volumes, $chapterCount chapters) to Downloads."
            }
        }
    }

    /**
     * The cover to put in an exported EPUB: an imported book's own (a data URL),
     * or a source's, fetched as the app shows it (the guarded image client, the
     * source's image headers). Best effort: no cover rather than no export.
     */
    private suspend fun coverForExport(cover: String?, pluginId: String?): Epub.Cover? = withContext(Dispatchers.IO) {
        runCatching {
            val c = cover?.takeIf { it.isNotBlank() } ?: return@runCatching null
            if (c.startsWith("data:")) {
                val type = c.removePrefix("data:").substringBefore(';').takeIf { it.startsWith("image/") }
                    ?: return@runCatching null
                return@runCatching Epub.Cover(java.util.Base64.getDecoder().decode(c.substringAfter("base64,")), type)
            }
            val request = okhttp3.Request.Builder().url(c).apply {
                Extensions.imageHeaders(pluginId).forEach { (k, v) -> header(k, v) }
            }.build()
            val client = imageClient().newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { r ->
                val type = r.body?.contentType()?.let { "${it.type}/${it.subtype}" }?.takeIf { it.startsWith("image/") }
                if (!r.isSuccessful || type == null) return@use null
                val source = r.body!!.source()
                source.request(MAX_COVER_BYTES + 1)
                if (source.buffer.size > MAX_COVER_BYTES) return@use null
                Epub.Cover(source.buffer.readByteArray(), type)
            }
        }.getOrNull()
    }

    // --- progress --------------------------------------------------------------

    fun setChapterRead(position: Int, read: Boolean) = setPositionsRead(listOf(position), read)

    fun setPositionsRead(positions: List<Int>, read: Boolean) {
        viewModelScope.launch { lib.setRead(bookId, positions, read) }
    }

    fun markAllRead() { viewModelScope.launch { lib.markAllRead(bookId) } }
    fun resetProgress() { viewModelScope.launch { lib.resetProgress(bookId) } }

    // --- library -----------------------------------------------------------------

    fun setRating(rating: Int) { viewModelScope.launch { lib.setRating(bookId, rating) } }

    fun toggleCollection(collectionId: Int) {
        val ids = book.value?.collectionIds ?: return
        val next = if (collectionId in ids) ids - collectionId else ids + collectionId
        viewModelScope.launch { lib.setBookCollections(bookId, next) }
    }

    fun addToLibrary() {
        viewModelScope.launch {
            lib.setInLibrary(bookId, true)
            _action.value = "Added to your library."
        }
    }

    /** Take a source novel out of the library, with its downloads. */
    fun removeFromLibrary() {
        viewModelScope.launch {
            lib.setInLibrary(bookId, false)
            lib.removeDownloads(bookId)
            _action.value = "Removed from your library."
        }
    }

    /** Delete an imported novel, which has no source to find it again, then
     *  [onDeleted]. */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            lib.delete(bookId)
            onDeleted()
        }
    }

    // --- downloads ---------------------------------------------------------------

    fun download(choice: DownloadChoice) {
        val list = chapters.value ?: return
        val positions = when (choice) {
            DownloadChoice.All -> null
            DownloadChoice.Unread -> list.filter { !it.read }.map { it.position }
            DownloadChoice.Next10 -> {
                val from = progress.value?.lastPosition ?: 1
                list.filter { it.position >= from && !it.downloaded }.take(10).map { it.position }
            }
        }
        if (positions?.isEmpty() == true) { _action.value = "Nothing to download."; return }
        // Downloading keeps the novel, so it joins the library.
        if (book.value?.inLibrary == false) addToLibrary()
        ChapterDownloads.download(bookId, positions)
    }

    fun cancelDownloads() { viewModelScope.launch { lib.cancelDownloads(bookId) } }

    fun removeDownloads() {
        viewModelScope.launch {
            lib.removeDownloads(bookId)
            _action.value = "Downloads removed."
        }
    }

    fun resumeDownloads() = ChapterDownloads.resume()

    /** Another go at the chapters that couldn't be downloaded. */
    fun retryFailedDownloads() = ChapterDownloads.retryFailed()

    fun forgetFailedDownloads() = ChapterDownloads.forgetFailed()

    // --- audiobook ----------------------------------------------------------------

    /** Read chapters out to audio files in Downloads. */
    fun exportAudio(range: AudioRange) {
        val list = chapters.value ?: return
        val book = book.value ?: return
        val from = progress.value?.lastPosition ?: 1
        val positions = when (range) {
            AudioRange.ThisChapter -> listOf(from)
            AudioRange.Next10 -> list.filter { it.position >= from }.take(10).map { it.position }
            AudioRange.All -> list.map { it.position }
        }
        if (positions.isEmpty()) { _action.value = "Nothing to save."; return }
        AudiobookExport.start(bookId, book.title, positions)
    }
}

/** The largest cover put in an exported EPUB. */
private const val MAX_COVER_BYTES = 5L * 1024 * 1024
