package com.novelscraper.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelscraper.app.data.BookStat
import com.novelscraper.app.data.StatsRead
import com.novelscraper.app.library.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale

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
     * Books and chapters come from the library itself, which holds every novel
     * and what has been read of it. Words need the text, so they are counted in
     * the chapters whose text is on this device (downloaded, or kept from
     * reading them).
     */
    private suspend fun libraryStats(): StatsRead = withContext(Dispatchers.IO) {
        val books = Library.store.libraryFlowOnce()
        var words = 0L
        for (b in books) words += Library.store.wordsRead(b.id)
        val wordsPerMinute = WORDS_PER_MINUTE
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

    /** The reading-progress export ("csv" | "json") as bytes; null on error.
     *  Written here from the library on this device, so it needs no account. */
    suspend fun export(format: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val s = libraryStats()
            val text = if (format == "json") toJson(s) else toCsv(s)
            text.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun toCsv(s: StatsRead): String = buildString {
        append("title,total_chapters,read_chapters,percent_read\n")
        for (b in s.books) {
            append(csvField(b.title)).append(',')
            append(b.total_chapters).append(',')
            append(b.read_count).append(',')
            append(String.format(Locale.ROOT, "%.1f", b.percent_read)).append('\n')
        }
    }

    /** RFC 4180: quote when the value holds a comma, quote or newline, and
     *  double any quote inside. */
    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            '"' + value.replace("\"", "\"\"") + '"'
        else value

    private fun toJson(s: StatsRead): String =
        Json { prettyPrint = true }.encodeToString(StatsRead.serializer(), s)
}

/** A typical adult's silent reading speed, for hours read where the server
 *  hasn't said what speed it counts by. */
private const val WORDS_PER_MINUTE = 250f
