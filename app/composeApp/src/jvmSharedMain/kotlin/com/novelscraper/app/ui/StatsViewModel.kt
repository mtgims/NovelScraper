package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.BookStat
import com.novelscraper.app.data.StatsRead
import com.novelscraper.app.library.Library
import com.novelscraper.app.net.Account
import com.novelscraper.app.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface StatsUi {
    data object Loading : StatsUi
    data class Error(val message: String) : StatsUi
    data class Data(val stats: StatsRead) : StatsUi
}

class StatsViewModel : ViewModel() {
    private val _ui = MutableStateFlow<StatsUi>(StatsUi.Loading)
    val ui: StateFlow<StatsUi> = _ui.asStateFlow()

    init { load() }

    fun load() {
        _ui.value = StatsUi.Loading
        viewModelScope.launch {
            _ui.value = try {
                StatsUi.Data(libraryStats())
            } catch (e: Exception) {
                StatsUi.Error("Couldn't load statistics.")
            }
        }
    }

    /**
     * The statistics of the whole library on this device.
     *
     * They used to be the server's alone, and the server knows only the novels
     * scraped or imported there: a novel added from a source was missing from
     * every figure. Books and chapters now come from the library itself, which
     * holds every novel and what has been read of it. Words need the text: the
     * server's figure stands for its own novels, whose text it has, and a
     * source novel's words are counted in the chapters whose text is on this
     * device (downloaded, or kept from reading them).
     */
    private suspend fun libraryStats(): StatsRead = withContext(Dispatchers.IO) {
        val books = Library.store.libraryFlowOnce()
        val server = if (Account.signedIn) runCatching { Net.api.stats() }.getOrNull() else null
        var words = server?.words_read?.toLong() ?: 0L
        for (b in books) {
            // With the server's figure in hand its novels are already counted.
            if (server != null && b.serverId != null) continue
            words += Library.store.wordsRead(b.id)
        }
        // Hours at the server's own reading speed when it says, else a typical one.
        val wordsPerMinute = server?.takeIf { it.words_read > 0 && it.hours_read > 0f }
            ?.let { it.words_read / (it.hours_read * 60f) } ?: WORDS_PER_MINUTE
        val perBook = books.map { b ->
            val read = b.chapterCount - b.unreadCount
            BookStat(b.id, b.title, b.chapterCount, read,
                if (b.chapterCount == 0) 0f else read * 100f / b.chapterCount)
        }
        val chapters = perBook.sumOf { it.total_chapters }
        val chaptersRead = perBook.sumOf { it.read_count }
        val hoursRead = words / wordsPerMinute / 60f
        StatsRead(
            total_books = perBook.size,
            books_started = perBook.count { it.read_count > 0 },
            books_finished = perBook.count { it.total_chapters > 0 && it.read_count == it.total_chapters },
            total_chapters = chapters,
            chapters_read = chaptersRead,
            // Words in unread chapters aren't known without their text.
            total_words = words.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            words_read = words.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            hours_read = hoursRead,
            hours_total = hoursRead,
            hours_remaining = 0f,
            percent_read = if (chapters == 0) 0f else chaptersRead * 100f / chapters,
            books = perBook,
        )
    }

    /** Fetch the reading-progress export ("csv" | "json") as bytes; null on error. */
    suspend fun export(format: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            Net.api.exportProgress(format).use { it.bytes() }
        } catch (e: Exception) {
            null
        }
    }
}

/** A typical adult's silent reading speed, for hours read where the server
 *  hasn't said what speed it counts by. */
private const val WORDS_PER_MINUTE = 250f
