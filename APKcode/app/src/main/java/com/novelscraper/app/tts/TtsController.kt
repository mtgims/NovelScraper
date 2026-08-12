package com.novelscraper.app.tts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-facing handle to [TtsService]. The UI observes [state] and issues commands
 * that are delivered to the foreground service via intents; the service writes
 * back into [state] so the reader's play/pause control and the media notification
 * stay in sync.
 */
object TtsController {

    data class State(
        val active: Boolean = false,   // service running with a loaded chapter
        val playing: Boolean = false,
        val bookId: Int = 0,
        val position: Int = 0,
        val chapterTitle: String = "",
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
    fun next(ctx: Context) = send(ctx, TtsService.ACTION_NEXT)
    fun prev(ctx: Context) = send(ctx, TtsService.ACTION_PREV)
    fun stop(ctx: Context) = send(ctx, TtsService.ACTION_STOP)

    private inline fun send(ctx: Context, action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(ctx, TtsService::class.java).setAction(action).apply(extras)
        ContextCompat.startForegroundService(ctx, intent)
    }
}
