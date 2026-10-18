package com.novelscraper.app.tts

/** How one sentence is spoken: one or more pieces, then a pause. */
class Direction(val pieces: List<Piece>, val pauseAfterMs: Int)

/**
 * A stretch of a sentence, in the narrator's voice or the dialogue voice, and
 * the silence after it. With [onset] above zero only the start of [text] is
 * kept, that share of the voiced audio: a stutter ("V-Violet") is the opening
 * sound of the word itself, which spelling out the letter can't give.
 */
data class Piece(
    val text: String,
    val dialogue: Boolean,
    val speedFactor: Float = 1f,
    val pauseAfterMs: Int = 0,
    val onset: Float = 0f,
)

/**
 * Directs the voices narration already has: which stretches of a sentence are
 * spoken as dialogue, how fast, and how long a pause follows. None of the
 * engines can be told how to feel, so this is all there is to direct.
 *
 * Only the desktop narrator is given one (expressive reading, a beta); without
 * one, every sentence is a single [NarratorVoice.generate] call as before.
 */
interface NarrationDirector {
    /** Whether novel [bookId] is directed: off unless the reader switched it on. */
    fun enabled(bookId: Int): Boolean

    /**
     * One entry per sentence of a chapter of [bookId], aligned with [spoken]
     * (null where spoken is null). [paragraphEnd] is true for a sentence that
     * ends its paragraph. Never adds or removes sentences: the highlight
     * follows their indices.
     */
    fun direct(bookId: Int, spoken: List<String?>, paragraphEnd: BooleanArray): List<Direction?>

    /** The dialogue speaker to pair with the narrator's [speaker] on [engine], in [bookId]. */
    fun dialogueSpeaker(bookId: Int, engine: String, speaker: Int): Int
}
