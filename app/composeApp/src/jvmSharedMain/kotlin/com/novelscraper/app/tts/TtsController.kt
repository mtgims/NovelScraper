package com.novelscraper.app.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-facing handle to narration. The UI observes [state] and issues commands; the
 * platform's player (on Android the TtsService foreground service, reached via
 * intents) carries them out and writes back into [state], so the reader's
 * "Listen" pill and the media notification stay in sync.
 */
object TtsController {

    data class State(
        val active: Boolean = false,   // player running with a loaded chapter
        val playing: Boolean = false,
        val bookId: Int = 0,
        val position: Int = 0,
        val chapterTitle: String = "",
        val sentenceIndex: Int = 0,
        val sentenceCount: Int = 0,
        val elapsedSec: Int = 0,
        val totalSec: Int = 0,
    )

    /** What a platform's narration player accepts. */
    interface Player {
        fun play(bookId: Int, position: Int, bookTitle: String, startIndex: Int)
        fun toggle()
        fun nextChapter()
        fun prevChapter()
        fun stop()
        fun seek(index: Int)
        /** Re-apply the current speech rate / voice to ongoing playback. */
        fun applySettings()
    }

    /** Installed by the platform at startup. */
    @Volatile var player: Player? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    internal fun update(s: State) { _state.value = s }
    internal fun clear() { _state.value = State() }

    fun play(bookId: Int, position: Int, bookTitle: String, startIndex: Int = 0) =
        player?.play(bookId, position, bookTitle, startIndex)

    fun toggle() = player?.toggle()
    fun nextChapter() = player?.nextChapter()
    fun prevChapter() = player?.prevChapter()
    fun stop() = player?.stop()
    fun seek(index: Int) = player?.seek(index)

    fun rewind() = seek((_state.value.sentenceIndex - 1).coerceAtLeast(0))
    fun forward() = seek(_state.value.sentenceIndex + 1)

    fun applySettings() = player?.applySettings()
}
