package com.novelscraper.app.tts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-facing handle to [TtsService]. The UI observes [state] and issues commands
 * delivered to the foreground service via intents; the service writes back into
 * [state] so the reader "Listen" pill + media notification stay in sync.
 */
object TtsController {

    data class State(
        val active: Boolean = false,   // service running with a loaded chapter
        val playing: Boolean = false,
        val bookId: Int = 0,
        val position: Int = 0,
        val chapterTitle: String = "",
        val sentenceIndex: Int = 0,
        val sentenceCount: Int = 0,
        val elapsedSec: Int = 0,
        val totalSec: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    internal fun update(s: State) { _state.value = s }
    internal fun clear() { _state.value = State() }

    fun play(ctx: Context, bookId: Int, position: Int, bookTitle: String) =
        send(ctx, TtsService.ACTION_PLAY) {
            putExtra(TtsService.EXTRA_BOOK_ID, bookId)
            putExtra(TtsService.EXTRA_POSITION, position)
            putExtra(TtsService.EXTRA_BOOK_TITLE, bookTitle)
        }

    fun toggle(ctx: Context) = send(ctx, TtsService.ACTION_TOGGLE)
    fun nextChapter(ctx: Context) = send(ctx, TtsService.ACTION_NEXT)
    fun prevChapter(ctx: Context) = send(ctx, TtsService.ACTION_PREV)
    fun stop(ctx: Context) = send(ctx, TtsService.ACTION_STOP)

    fun seek(ctx: Context, index: Int) =
        send(ctx, TtsService.ACTION_SEEK) { putExtra(TtsService.EXTRA_INDEX, index) }

    fun rewind(ctx: Context) = seek(ctx, (_state.value.sentenceIndex - 1).coerceAtLeast(0))
    fun forward(ctx: Context) = seek(ctx, _state.value.sentenceIndex + 1)

    /** Re-apply the current speech rate / voice to ongoing playback. */
    fun applySettings(ctx: Context) = send(ctx, TtsService.ACTION_SETRATE)

    private inline fun send(ctx: Context, action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(ctx, TtsService::class.java).setAction(action).apply(extras)
        ContextCompat.startForegroundService(ctx, intent)
    }
}
