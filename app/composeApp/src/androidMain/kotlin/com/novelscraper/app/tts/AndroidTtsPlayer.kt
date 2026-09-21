package com.novelscraper.app.tts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** [TtsController.Player] for Android: every command is an intent to the
 *  [TtsService] foreground service. */
class AndroidTtsPlayer(private val ctx: Context) : TtsController.Player {

    override fun play(bookId: Int, position: Int, bookTitle: String, startIndex: Int) =
        send(TtsService.ACTION_PLAY) {
            putExtra(TtsService.EXTRA_BOOK_ID, bookId)
            putExtra(TtsService.EXTRA_POSITION, position)
            putExtra(TtsService.EXTRA_BOOK_TITLE, bookTitle)
            putExtra(TtsService.EXTRA_INDEX, startIndex)
        }

    override fun toggle() = send(TtsService.ACTION_TOGGLE)
    override fun nextChapter() = send(TtsService.ACTION_NEXT)
    override fun prevChapter() = send(TtsService.ACTION_PREV)
    override fun stop() = send(TtsService.ACTION_STOP)

    override fun seek(index: Int) =
        send(TtsService.ACTION_SEEK) { putExtra(TtsService.EXTRA_INDEX, index) }

    override fun applySettings() = send(TtsService.ACTION_SETRATE)

    private inline fun send(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(ctx, TtsService::class.java).setAction(action).apply(extras)
        ContextCompat.startForegroundService(ctx, intent)
    }
}
