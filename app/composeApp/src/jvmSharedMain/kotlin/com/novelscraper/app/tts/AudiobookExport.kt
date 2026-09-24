package com.novelscraper.app.tts

import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.data.Sentences
import com.novelscraper.app.library.Library
import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.appCacheDir
import com.novelscraper.app.platform.saveToDownloads
import com.novelscraper.app.platform.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Saves chapters as audio files (one WAV per chapter, in Downloads), read by the
 * same on-device voice, junk filter and pronunciation dictionary as narration,
 * so a novel can be listened to in a car or on a player that isn't this app.
 *
 * WAV, because the app has no encoder for anything smaller: about 3 MB a minute.
 * Synthesis is slower than listening on most devices, so this takes a while and
 * runs in the background with progress and a cancel.
 */
object AudiobookExport {

    data class State(
        val running: Boolean = false,
        val bookId: Int = 0,
        /** Chapters finished, of how many. */
        val done: Int = 0,
        val total: Int = 0,
        /** The chapter being read now. */
        val chapter: String = "",
        val lastFile: String? = null,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    /** Export [positions] of a novel. One file each; safe to call while reading. */
    fun start(bookId: Int, bookTitle: String, positions: List<Int>, voice: NarratorVoice = KokoroVoice) {
        if (job?.isActive == true) {
            showToast("An export is already running.")
            return
        }
        if (positions.isEmpty()) return
        _state.value = State(running = true, bookId = bookId, total = positions.size)
        job = scope.launch {
            val problem = voice.prepare(modelId())
            if (problem != null) {
                _state.value = _state.value.copy(running = false, error = problem)
                showToast(problem, long = true)
                return@launch
            }
            var done = 0
            for (position in positions) {
                if (!coroutineContext.isActive) break
                try {
                    val chapter = Library.store.chapter(bookId, position)
                    _state.value = _state.value.copy(chapter = chapter.title, done = done)
                    val plain = Sentences.plain(chapter.content)
                    val sentences = Sentences.ranges(plain).map { plain.substring(it.first, it.last + 1) }
                    val lines = NarrationText.spokenLines(sentences).filterNotNull()
                    if (lines.isEmpty()) { done++; continue }
                    val name = fileName(bookTitle, position, chapter.title)
                    val saved = saveToDownloads(name, "audio/wav") { out ->
                        writeWav(out, voice, lines)
                    }
                    done++
                    _state.value = _state.value.copy(done = done, lastFile = saved)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "export chapter $position: ${e.message}")
                    _state.value = _state.value.copy(running = false, error = "Couldn't export chapter $position.")
                    showToast("Audio export stopped: couldn't read chapter $position.", long = true)
                    return@launch
                }
            }
            _state.value = _state.value.copy(running = false, done = done)
            showToast(
                if (done == 1) "Saved 1 chapter to Downloads." else "Saved $done chapters to Downloads.",
                long = true,
            )
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false)
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    private fun modelId() = ReaderPrefs.modelFor(ReaderPrefs.ttsEngine.value)

    private fun speaker() = ReaderPrefs.speakerFor(ReaderPrefs.ttsEngine.value)

    /** "The Novel - 012 Chapter title.wav", safe on every file system. */
    internal fun fileName(bookTitle: String, position: Int, chapterTitle: String): String {
        val safe = { s: String -> s.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").trim().take(60) }
        val num = position.toString().padStart(3, '0')
        return "${safe(bookTitle)} - $num ${safe(chapterTitle)}.wav".replace(Regex(" +"), " ")
    }

    /**
     * A 16-bit mono WAV of [lines]. WAV puts its size in the header, so the audio
     * is synthesized into a scratch file first (chapters run to tens of MB) and
     * then copied out behind the header; the scratch file goes either way.
     */
    private suspend fun writeWav(out: OutputStream, voice: NarratorVoice, lines: List<String>) {
        val rate = voice.sampleRate
        val speed = ReaderPrefs.ttsRate.value.coerceIn(0.5f, 2.5f)
        val speaker = speaker()
        val scratch = java.io.File.createTempFile("narration", ".pcm", appCacheDir())
        try {
            scratch.outputStream().buffered().use { raw ->
                for (line in lines) {
                    if (!coroutineContext.isActive) break
                    val pcm = withContext(Dispatchers.Default) { voice.generate(line, speaker, speed) }
                    val bytes = ByteArray(pcm.size * 2)
                    for (i in pcm.indices) {
                        val v = (pcm[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                        bytes[2 * i] = v.toByte()
                        bytes[2 * i + 1] = (v shr 8).toByte()
                    }
                    raw.write(bytes)
                }
            }
            out.write(wavHeader(rate, scratch.length().toInt()))
            scratch.inputStream().buffered().use { it.copyTo(out) }
            out.flush()
        } finally {
            scratch.delete()
        }
    }

    /** Canonical 44-byte WAV header: mono, 16-bit, [rate] Hz, [dataSize] bytes. */
    internal fun wavHeader(rate: Int, dataSize: Int): ByteArray {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)                 // PCM header size
        header.putShort(1)                // PCM
        header.putShort(1)                // mono
        header.putInt(rate)
        header.putInt(rate * 2)           // bytes per second
        header.putShort(2)                // block align
        header.putShort(16)               // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)
        return header.array()
    }

    private const val TAG = "AudioExport"
}
