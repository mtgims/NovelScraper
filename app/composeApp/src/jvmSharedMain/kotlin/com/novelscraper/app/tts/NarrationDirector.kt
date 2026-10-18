package com.novelscraper.app.tts

/** How one sentence is spoken: one or more pieces, then a pause. */
class Direction(val pieces: List<Piece>, val pauseAfterMs: Int)

/** Who speaks a piece: the narrator, a character, or a LitRPG system message. */
enum class Role { NARRATOR, DIALOGUE, SYSTEM }

/**
 * A stretch of a sentence, in the voice of its [role], and the silence after it. With [onset] above zero only the start of [text] is
 * kept, that share of the voiced audio: a stutter ("V-Violet") is the opening
 * sound of the word itself, which spelling out the letter can't give. [gain]
 * makes it quieter or louder than its voice's usual level (a whisper, a shout).
 */
data class Piece(
    val text: String,
    val role: Role,
    val speedFactor: Float = 1f,
    val pauseAfterMs: Int = 0,
    val onset: Float = 0f,
    val gain: Float = 1f,
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

    /** The speaker for [role] in [bookId] on [engine], where the narrator is [narrator]. */
    fun speakerFor(bookId: Int, engine: String, narrator: Int, role: Role): Int
}
