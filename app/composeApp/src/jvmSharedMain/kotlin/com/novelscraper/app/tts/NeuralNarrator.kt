package com.novelscraper.app.tts

import com.novelscraper.app.data.ChapterRead
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.data.Sentences
import com.novelscraper.app.library.Library
import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** The synthesizer the narrator speaks with ([KokoroEngine] in the app). */
interface NarratorVoice {
    /** Load [modelId]; null when ready, else a message for the user. */
    fun prepare(modelId: String): String?
    val sampleRate: Int
    fun generate(text: String, speaker: Int, speed: Float): FloatArray
    fun release()
}

/** The on-device Kokoro/Piper models. */
object KokoroVoice : NarratorVoice {
    override fun prepare(modelId: String): String? = when {
        !TtsModels.isModelReady(modelId) -> "Download a voice first: Settings, Narration."
        !KokoroEngine.ensureLoaded(modelId) -> "Couldn't load the voice."
        else -> null
    }
    override val sampleRate get() = KokoroEngine.sampleRate
    override fun generate(text: String, speaker: Int, speed: Float) = KokoroEngine.generate(text, speaker, speed)
    override fun release() = KokoroEngine.release()
}

/** Where synthesized audio goes: a platform audio output, or a file in tests. */
interface PcmSink {
    /** Queue mono float frames, blocking while the output buffer is full (which
     *  paces the writer to real time). */
    fun write(pcm: FloatArray)

    /** Frames the listener has actually heard so far. */
    val framesPlayed: Long

    /** Stop at once and discard anything buffered. */
    fun close()
}

/**
 * Narration with the on-device Kokoro/Piper models ([KokoroVoice]).
 *
 * One run of playback streams sentence after sentence and **chapter after
 * chapter** into a single open [PcmSink]: while the end of a chapter is still
 * being heard, the next one is already fetched and synthesized, so a novel plays
 * without a gap at the chapter line. Sentences the reader does not want spoken
 * (site plugs, translator notes; see [NarrationText]) are passed over.
 *
 * What is heard, not what has been generated, drives the UI: every piece of audio
 * is written with a mark (frame, chapter, sentence), and a ticker maps the sink's
 * playback head back to those marks, so the reader's highlight, the media
 * notification and the resume point follow the voice. A chapter counts as read
 * when its first audio is heard.
 *
 * Every state change runs on one thread ([state]); synthesis and writing run on
 * their own. Each (re)start bumps [epoch], so a superseded run that is still
 * winding down never acts on the new one's state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NeuralNarrator(
    private val openSink: (sampleRate: Int) -> PcmSink,
    private val voice: NarratorVoice = KokoroVoice,
    private val fetchChapter: suspend (bookId: Int, position: Int) -> ChapterRead =
        { b, p -> Library.store.chapter(b, p) },
    private val markRead: suspend (bookId: Int, position: Int) -> Unit =
        { b, p -> Library.store.markOpened(b, p) },
) : TtsController.Player {

    private val state = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + state)

    /** A chapter ready to be spoken. */
    private class Loaded(
        val position: Int,
        val title: String,
        val sentences: List<String>,
        /** Per sentence: what to say, or null to pass over it. */
        val spoken: List<String?>,
        val hasPrev: Boolean,
        val hasNext: Boolean,
    ) {
        val cumChars = IntArray(sentences.size).also {
            var acc = 0
            for (i in sentences.indices) { it[i] = acc; acc += sentences[i].length }
        }
        val totalChars = sentences.sumOf { it.length }
    }

    /** Where the listener is: used by the ticker to move the UI. */
    private class Mark(val frame: Long, val chapter: Loaded, val sentence: Int)

    private var bookId = 0
    private var current: Loaded? = null
    private var index = 0
    private var playing = false
    private var engine = ReaderPrefs.ENGINE_PIPER

    private var epoch = 0
    private var playJob: Job? = null
    private var sink: PcmSink? = null

    // Chapters are marked read in the order they are heard: the last one marked
    // is the resume point, so this must not run in parallel.
    private val heard = Channel<Int>(Channel.UNLIMITED)
    private val marker = scope.launch(Dispatchers.IO) {
        for (position in heard) runCatching { markRead(bookId, position) }
    }

    override fun play(bookId: Int, position: Int, bookTitle: String, startIndex: Int) {
        scope.launch {
            this@NeuralNarrator.bookId = bookId
            start(position, startIndex)
        }
    }

    override fun toggle() {
        scope.launch { if (playing) pauseNow() else resumeNow() }
    }

    override fun nextChapter() { scope.launch { skip(+1) } }
    override fun prevChapter() { scope.launch { skip(-1) } }
    override fun stop() { scope.launch { stopNow() } }

    override fun seek(index: Int) {
        scope.launch { current?.let { start(it.position, index) } }
    }

    override fun applySettings() {
        scope.launch { if (playing) current?.let { start(it.position, index) } }
    }

    /** Stop and free the model (the app is closing). */
    fun release() {
        scope.launch { stopNow() }
        voice.release()
    }

    // --- playback (all on `state`) ---------------------------------------

    /** Play from [position], sentence [from], until the novel ends or it is stopped. */
    private fun start(position: Int, from: Int) {
        stopAudio()
        val my = ++epoch
        playing = true
        playJob = scope.launch {
            val first = load(position) ?: return@launch
            if (my != epoch) return@launch
            current = first
            index = from.coerceIn(0, maxOf(first.sentences.size - 1, 0))
            publish()

            val problem = withContext(Dispatchers.Default) { voice.prepare(modelId()) }
            if (problem != null) {
                showToast(problem, long = true)
                stopNow(); return@launch
            }
            val out = try {
                openSink(voice.sampleRate)
            } catch (e: Exception) {
                Log.w(TAG, "no audio output: ${e.message}")
                showToast("No audio output available: ${e.message ?: "unknown error"}", long = true)
                stopNow(); return@launch
            }
            sink = out
            stream(my, out, first, index)
        }
    }

    /** Synthesize ahead, write to [out], and follow what is heard. */
    private suspend fun stream(my: Int, out: PcmSink, first: Loaded, from: Int) {
        // A few sentences ahead, so one slow sentence (or fetching the next
        // chapter) doesn't starve the output.
        val queue = Channel<Triple<Loaded, Int, FloatArray>>(capacity = 6)
        // Written in order; the ticker walks forward through them.
        val marks = java.util.Collections.synchronizedList(ArrayList<Mark>())

        val producer = scope.launch(Dispatchers.Default) {
            var chapter = first
            var start = from
            while (isActive) {
                for (i in start until chapter.sentences.size) {
                    if (!isActive) return@launch
                    val text = chapter.spoken.getOrNull(i) ?: continue   // passed over
                    queue.send(Triple(chapter, i, voice.generate(text, speaker(), speed())))
                }
                // The chapter is synthesized; roll into the next one while it plays.
                val more = chapter.hasNext && ReaderPrefs.ttsAutoNext.value && !SleepTimer.stopAtChapterEnd
                if (!more) break
                chapter = load(chapter.position + 1) ?: break
                start = 0
            }
            queue.close()
        }

        // The UI follows the playback head, not the producer.
        var cursor = 0              // the last mark reached
        var shownChapter = -1
        /** Move the UI to whatever has been heard by now. */
        fun advance() {
            val reached = ArrayList<Mark>()
            synchronized(marks) {
                while (cursor < marks.size && marks[cursor].frame <= out.framesPlayed) reached.add(marks[cursor++])
            }
            if (reached.isEmpty() || my != epoch) return
            // Every chapter whose audio has been heard counts as read, even if
            // several went by between two ticks.
            for (m in reached) {
                if (m.chapter.position == shownChapter) continue
                shownChapter = m.chapter.position
                current = m.chapter
                heard.trySend(m.chapter.position)
            }
            index = reached.last().sentence
            publish()
        }

        val ticker = scope.launch {
            while (isActive) { advance(); delay(120) }
        }

        try {
            val written = withContext(Dispatchers.IO) {
                var frames = 0L
                for ((chapter, i, pcm) in queue) {
                    if (!isActive) break
                    synchronized(marks) { marks.add(Mark(frames, chapter, i)) }
                    out.write(pcm)
                    frames += pcm.size
                }
                frames
            }
            // Let the buffered tail play out before ending.
            while (coroutineContext.isActive && out.framesPlayed < written) delay(60)
            ticker.cancel()
            advance()   // the last sentences, however fast they played
            if (coroutineContext.isActive && my == epoch) finished()
        } catch (e: Exception) {
            if (my == epoch) {
                Log.w(TAG, "playback failed: ${e.message}")
                showToast("Narration stopped: ${e.message ?: "audio error"}")
                stopNow()
            }
        } finally {
            producer.cancel()
            ticker.cancel()
        }
    }

    /** Fetch a chapter and work out what to say; null (and stops) if it can't. */
    private suspend fun load(position: Int): Loaded? {
        val chapter = try {
            withContext(Dispatchers.IO) { fetchChapter(bookId, position) }
        } catch (e: Exception) {
            showToast("Couldn't load the chapter to narrate.")
            stopNow(); return null
        }
        val plain = Sentences.plain(chapter.content)
        val sentences = Sentences.ranges(plain).map { plain.substring(it.first, it.last + 1) }
        val loaded = Loaded(
            position = position, title = chapter.title, sentences = sentences,
            spoken = NarrationText.spokenLines(sentences),
            hasPrev = chapter.has_prev, hasNext = chapter.has_next,
        )
        // Nothing to say (an empty chapter, or all of it passed over): move on.
        if (loaded.spoken.all { it == null }) {
            return if (loaded.hasNext && ReaderPrefs.ttsAutoNext.value) load(position + 1) else null
        }
        return loaded
    }

    private fun modelId() = ReaderPrefs.modelFor(ReaderPrefs.ttsEngine.value)

    private fun speaker(): Int {
        engine = ReaderPrefs.ttsEngine.value
        // Piper voices are single-speaker; Kokoro and Supertonic use the picked id.
        return ReaderPrefs.speakerFor(engine)
    }

    private fun speed() = ReaderPrefs.ttsRate.value.coerceIn(0.5f, 2.5f)

    private fun resumeNow() {
        val c = current ?: return
        start(c.position, index)
    }

    private fun pauseNow() {
        if (!playing) return
        playing = false
        stopAudio()
        publish()
    }

    private fun skip(delta: Int) {
        val c = current ?: return
        if (delta > 0 && !c.hasNext) return
        if (delta < 0 && !c.hasPrev) return
        val target = c.position + delta
        if (target < 1) return
        start(target, 0)
    }

    /** The novel (or the chapter, without auto-next) ended on its own. */
    private fun finished() {
        SleepTimer.chapterEnded()
        stopNow()
    }

    private fun stopNow() {
        playing = false
        stopAudio()
        current = null
        SleepTimer.narrationStopped()
        TtsController.clear()
    }

    private fun stopAudio() {
        epoch++
        playJob?.cancel(); playJob = null
        sink?.let { runCatching { it.close() } }
        sink = null
    }

    private fun publish() {
        val c = current ?: return
        val rate = ReaderPrefs.ttsRate.value.coerceAtLeast(0.1f)
        val before = if (index in c.cumChars.indices) c.cumChars[index] else c.totalChars
        TtsController.update(
            TtsController.State(
                active = true, playing = playing,
                bookId = bookId, position = c.position, chapterTitle = c.title,
                sentenceIndex = index, sentenceCount = c.sentences.size,
                elapsedSec = (before * SEC_PER_CHAR / rate).toInt(),
                totalSec = (c.totalChars * SEC_PER_CHAR / rate).toInt(),
            ),
        )
    }

    private companion object {
        const val TAG = "Narrator"
        const val SEC_PER_CHAR = 0.06f
    }
}
