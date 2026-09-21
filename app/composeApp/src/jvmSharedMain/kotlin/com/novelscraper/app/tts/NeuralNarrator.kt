package com.novelscraper.app.tts

import com.novelscraper.app.data.ChapterRead
import com.novelscraper.app.data.ProgressUpdate
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.data.Sentences
import com.novelscraper.app.net.Net
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
 * Narration with the on-device Kokoro/Piper models ([KokoroVoice]), for platforms
 * without an Android foreground service (the desktop app). Mirrors the neural path
 * of Android's TtsService: fetch the chapter, split it with the shared [Sentences]
 * rules, synthesize a few sentences ahead into a bounded queue, stream to a
 * [PcmSink], and move the reader's highlight by what has actually been heard.
 * Listening marks the chapter read, and a finished chapter rolls into the next when
 * "Auto next chapter" is on.
 *
 * Every state change runs on one thread ([state]); audio writing and synthesis run
 * on their own. Each (re)start of playback bumps [epoch], so a superseded playback
 * that is still winding down never acts on the new one's state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NeuralNarrator(
    private val openSink: (sampleRate: Int) -> PcmSink,
    private val voice: NarratorVoice = KokoroVoice,
    private val fetchChapter: suspend (bookId: Int, position: Int) -> ChapterRead =
        { b, p -> Net.api.chapter(b, p) },
    private val markRead: suspend (bookId: Int, position: Int) -> Unit =
        { b, p -> Net.api.putProgress(b, ProgressUpdate(last_position = p, mark_read = p)) },
) : TtsController.Player {

    private val state = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + state)

    private var bookId = 0
    private var position = 0
    private var chapterTitle = ""
    private var sentences: List<String> = emptyList()
    private var cumChars = IntArray(0)   // chars before sentence i (for the time estimate)
    private var totalChars = 0
    private var index = 0
    private var hasNext = false
    private var hasPrev = false
    private var playing = false
    private var engine = ReaderPrefs.ENGINE_PIPER

    private var epoch = 0
    private var loadJob: Job? = null
    private var playJob: Job? = null
    private var sink: PcmSink? = null

    override fun play(bookId: Int, position: Int, bookTitle: String, startIndex: Int) {
        scope.launch {
            this@NeuralNarrator.bookId = bookId
            load(position, startIndex)
        }
    }

    override fun toggle() {
        scope.launch { if (playing) pauseNow() else resumeNow() }
    }

    override fun nextChapter() { scope.launch { skip(+1) } }
    override fun prevChapter() { scope.launch { skip(-1) } }
    override fun stop() { scope.launch { stopNow() } }

    override fun seek(index: Int) {
        scope.launch { if (sentences.isNotEmpty()) speakFrom(index) }
    }

    override fun applySettings() {
        scope.launch { if (playing) speakFrom(index) }
    }

    /** Stop and free the model (the app is closing). */
    fun release() {
        scope.launch { stopNow() }
        voice.release()
    }

    // --- playback (all on `state`) ---------------------------------------

    private fun load(pos: Int, startIndex: Int) {
        stopAudio()
        loadJob?.cancel()
        loadJob = scope.launch {
            val chapter = try {
                withContext(Dispatchers.IO) { fetchChapter(bookId, pos) }
            } catch (e: Exception) {
                showToast("Couldn't load the chapter to narrate.")
                stopNow(); return@launch
            }
            position = pos
            chapterTitle = chapter.title
            hasNext = chapter.has_next
            hasPrev = chapter.has_prev
            val plain = Sentences.plain(chapter.content)
            sentences = Sentences.ranges(plain).map { plain.substring(it.first, it.last + 1) }
            cumChars = IntArray(sentences.size)
            var acc = 0
            for (i in sentences.indices) { cumChars[i] = acc; acc += sentences[i].length }
            totalChars = acc
            index = 0
            // Listening marks the chapter read + moves the resume point.
            scope.launch(Dispatchers.IO) {
                runCatching { markRead(bookId, pos) }
            }
            if (sentences.isEmpty()) { skip(+1); return@launch }

            engine = ReaderPrefs.ttsEngine.value
            val modelId = if (engine == ReaderPrefs.ENGINE_KOKORO) TtsModels.KOKORO else ReaderPrefs.piperVoice.value
            val problem = withContext(Dispatchers.Default) { voice.prepare(modelId) }
            if (problem != null) {
                showToast(problem, long = true)
                stopNow(); return@launch
            }
            speakFrom(startIndex)
        }
    }

    private fun speakFrom(from: Int) {
        stopAudio()
        index = from.coerceIn(0, sentences.size)
        if (index >= sentences.size) { onChapterFinished(); return }
        val out = try {
            openSink(voice.sampleRate)
        } catch (e: Exception) {
            Log.w(TAG, "no audio output: ${e.message}")
            showToast("No audio output available: ${e.message ?: "unknown error"}", long = true)
            stopNow(); return
        }
        sink = out
        playing = true
        publish()

        val my = ++epoch
        // Piper voices are single-speaker; Kokoro uses the picked speaker id.
        val speaker = if (engine == ReaderPrefs.ENGINE_KOKORO) ReaderPrefs.kokoroSpeaker.value else 0
        val speed = ReaderPrefs.ttsRate.value.coerceIn(0.5f, 2.5f)
        val start = index
        val lines = sentences
        val frameStarts = LongArray(lines.size) { -1L }  // audible start frame per sentence

        playJob = scope.launch {
            // Synthesize several sentences ahead so one slow sentence doesn't starve
            // the output.
            val queue = Channel<Pair<Int, FloatArray>>(capacity = 6)
            val producer = launch(Dispatchers.Default) {
                for (i in start until lines.size) {
                    if (!isActive) break
                    queue.send(i to voice.generate(lines[i], speaker, speed))
                }
                queue.close()
            }
            // Highlight: the sentence under the playback head.
            val ticker = launch {
                var shown = -1
                while (isActive) {
                    val head = out.framesPlayed
                    var cur = shown
                    for (i in start until lines.size) {
                        val s = frameStarts[i]
                        if (s < 0) break
                        if (s <= head) cur = i else break
                    }
                    if (cur >= 0 && cur != shown && my == epoch) { shown = cur; index = cur; publish() }
                    delay(120)
                }
            }
            try {
                val written = withContext(Dispatchers.IO) {
                    var frames = 0L
                    for ((i, pcm) in queue) {
                        if (!isActive) break
                        frameStarts[i] = frames
                        out.write(pcm)
                        frames += pcm.size
                    }
                    frames
                }
                // Let the buffered tail play out before moving on.
                while (isActive && out.framesPlayed < written) delay(60)
                if (isActive && my == epoch) onChapterFinished()
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
    }

    private fun resumeNow() {
        if (playing || sentences.isEmpty()) return
        speakFrom(index)
    }

    private fun pauseNow() {
        if (!playing) return
        playing = false
        stopAudio()
        publish()
    }

    private fun skip(delta: Int) {
        if (delta > 0 && !hasNext) return
        if (delta < 0 && !hasPrev) return
        val target = position + delta
        if (target < 1) return
        load(target, 0)
    }

    private fun onChapterFinished() {
        if (hasNext && ReaderPrefs.ttsAutoNext.value) skip(+1) else stopNow()
    }

    private fun stopNow() {
        playing = false
        loadJob?.cancel(); loadJob = null
        stopAudio()
        sentences = emptyList()
        TtsController.clear()
    }

    private fun stopAudio() {
        epoch++
        playJob?.cancel(); playJob = null
        sink?.let { runCatching { it.close() } }
        sink = null
    }

    private fun publish() {
        val rate = ReaderPrefs.ttsRate.value.coerceAtLeast(0.1f)
        val before = if (index in cumChars.indices) cumChars[index] else totalChars
        TtsController.update(
            TtsController.State(
                active = true, playing = playing,
                bookId = bookId, position = position, chapterTitle = chapterTitle,
                sentenceIndex = index, sentenceCount = sentences.size,
                elapsedSec = (before * SEC_PER_CHAR / rate).toInt(),
                totalSec = (totalChars * SEC_PER_CHAR / rate).toInt(),
            ),
        )
    }

    private companion object {
        const val TAG = "Narrator"
        const val SEC_PER_CHAR = 0.06f
    }
}
