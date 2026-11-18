package com.novelscraper.app.tts

import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.showToast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * On-device neural TTS via sherpa-onnx, running one of the models in
 * [TtsModels] (Kokoro or a Piper voice). Each platform builds the native model
 * with its own sherpa-onnx binding ([buildOfflineTts]); the rest is shared.
 *
 * Only one model is loaded at a time (switching rebuilds). Not thread-safe:
 * generate() is serialized with load/release via the object monitor.
 */
object KokoroEngine {
    private const val TAG = "KokoroEngine"
    @Volatile private var tts: TtsModel? = null
    @Volatile private var loadedId: String? = null

    val sampleRate: Int get() = tts?.sampleRate ?: 24000
    val numSpeakers: Int get() = tts?.numSpeakers ?: 0

    private fun defaultThreads(): Int = defaultTtsThreads()

    /** Set when the way the model should be built has changed (the desktop's
     *  GPU pack finished testing): the next [generate] or [ensureLoaded]
     *  builds it afresh. A plain flag, so it can be set without waiting on a
     *  generation. */
    @Volatile private var reload = false

    fun reloadOnNextUse() { reload = true }

    /** The desktop's watch on the graphics card's memory; none elsewhere. */
    interface CardWatch {
        /** Asked before each sentence: why the voice has to leave the card
         *  now (the next build then uses the processor), or null to stay. */
        fun leaveCard(): String?
        /** Asked when narration starts: true when a voice that left the card
         *  can go back, there being room again. */
        fun backToCard(): Boolean
    }

    @Volatile var cardWatch: CardWatch? = null

    private val _notice = MutableStateFlow<String?>(null)
    /** Something the reader should see while narration plays, such as the
     *  voice having moved to the processor; null when there is nothing. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun dismissNotice() { _notice.value = null }

    /** For a build that couldn't use the card it was meant to. */
    fun notice(message: String) { _notice.value = message }

    /** How a model is built; replaced in tests. */
    internal var build: (TtsModels.Spec, String, Int) -> TtsModel = ::buildOfflineTts

    /** The threads the loaded model was asked for, to rebuild it the same way. */
    private var loadedThreads = 0

    /**
     * The one thread the native model is built, run and released on. ONNX
     * Runtime's CUDA provider keeps working memory for each thread that runs
     * a model, about 1 GB each over time, and gives none of it back while the
     * model lives. Narration generates from a coroutine pool, so sentences
     * landed on worker after worker: on 2026-11-17 the card filled after 37
     * minutes, the runtime aborted the app, and the driver was left refusing
     * new windows. On one thread the card holds a flat 2.2 GB.
     */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "narration-voice").apply { isDaemon = true }
    }
    @Volatile private var workerThread: Thread? = null

    /** Runs [block] on [worker] and waits for it. The wait ignores interrupts
     *  (and restores the flag after): a caller giving up mid-sentence must not
     *  leave a build or release running behind its back. */
    private fun <T> onWorker(block: () -> T): T {
        if (Thread.currentThread() === workerThread) return block()
        val task = worker.submit(java.util.concurrent.Callable { workerThread = Thread.currentThread(); block() })
        var interrupted = false
        try {
            while (true) {
                try {
                    return task.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * Load [modelId] ("kokoro" or a Piper voice id), rebuilding if a different one
     * is active. False if the model isn't present or native init throws (caller
     * falls back to device TTS). Plain CPU EP (XNNPACK measured slower here).
     */
    @Synchronized
    fun ensureLoaded(modelId: String, numThreads: Int = defaultThreads()): Boolean {
        val loaded = tts != null && loadedId == modelId
        val back = loaded && cardWatch?.backToCard() == true
        if (loaded && !reload && !back) return true
        if (back) { _notice.value = null; Log.i(TAG, "room on the graphics card again: moving back") }
        reload = false
        tts?.let { old -> runCatching { onWorker { old.release() } } }
        tts = null; loadedId = null
        if (!TtsModels.isModelReady(modelId)) return false
        val threads = numThreads.coerceIn(1, 8)
        val built = tryBuild(modelId, threads) ?: return false
        tts = built
        loadedId = modelId
        loadedThreads = threads
        Log.i(TAG, "loaded id=$modelId threads=$threads sr=$sampleRate speakers=$numSpeakers")
        return true
    }

    /**
     * Rebuilds the loaded model between two sentences when [reloadOnNextUse]
     * asked for it, so narration already under way moves to the graphics card
     * once the card has been tried, instead of at the next Listen: the test
     * runs while the processor reads, and a long listen could stay there for
     * hours. The new model is built before the old one goes, so a build that
     * fails leaves the voice that was playing.
     */
    private fun rebuildIfAsked() {
        if (!reload) return
        val id = loadedId ?: return
        reload = false
        val built = tryBuild(id, loadedThreads.coerceAtLeast(1)) ?: return
        tts?.let { old -> runCatching { onWorker { old.release() } } }
        tts = built
        Log.i(TAG, "reloaded id=$id while narrating")
    }

    /**
     * Moves the voice to the processor when [cardWatch] says the card is
     * running out of memory. ONNX Runtime's out-of-memory error escapes the
     * native library and aborts the whole app, and a card filled to the brim
     * once left the driver refusing new windows, so this happens before the
     * card is full. The card's model is released first: its memory is what
     * the card is short of.
     */
    private fun leaveCardIfShort() {
        val reason = cardWatch?.leaveCard() ?: return
        val id = loadedId ?: return
        Log.w(TAG, "moving narration to the processor: $reason")
        tts?.let { old -> runCatching { onWorker { old.release() } } }
        tts = tryBuild(id, loadedThreads.coerceAtLeast(1))
        if (tts == null) loadedId = null
        _notice.value = reason
        showToast(reason, long = true)
    }

    private fun tryBuild(modelId: String, threads: Int): TtsModel? {
        return try {
            val spec = TtsModels.spec(modelId)
            val dir = TtsModels.modelDir(modelId).absolutePath
            onWorker { build(spec, dir, threads) }
        } catch (t: Throwable) {
            Log.w(TAG, "build failed (id=$modelId): ${t.message}")
            null
        }
    }

    /**
     * Synthesize one text span to mono float PCM at [sampleRate]. Empty on failure.
     * `@Synchronized` (same monitor as [ensureLoaded]/[release]) so generation is
     * single-flight — ONNX Runtime is not thread-safe, and this also prevents
     * [release] freeing the engine while a generate is in flight (use-after-free).
     */
    @Synchronized
    fun generate(text: String, speaker: Int, speed: Float): FloatArray {
        rebuildIfAsked()
        leaveCardIfShort()
        val engine = tts ?: return FloatArray(0)
        // Drop image placeholders — an illustration is its own "sentence" in the
        // shared split, but there's nothing to speak; treat it as silence.
        val t = text.replace(com.novelscraper.app.data.Sentences.OBJ, ' ').trim()
        if (t.isEmpty()) return FloatArray(0)
        val sid = speaker.coerceIn(0, (numSpeakers - 1).coerceAtLeast(0))
        return try {
            val t0 = System.nanoTime()
            val pace = speed.coerceIn(0.5f, 2.5f)
            val samples = onWorker { engine.generate(t, sid, pace) }
            condition(samples)
            val inferMs = (System.nanoTime() - t0) / 1_000_000.0
            val audioSec = samples.size.toDouble() / engine.sampleRate.coerceAtLeast(1)
            if (audioSec > 0) {
                Log.i(TAG, "gen chars=${t.length} infer=${inferMs.toInt()}ms " +
                    "audio=${"%.2f".format(audioSec)}s RTF=${"%.2f".format(inferMs / 1000.0 / audioSec)}")
            }
            samples
        } catch (t2: Throwable) {
            Log.e(TAG, "generate failed", t2)
            FloatArray(0)
        }
    }

    /**
     * Clean up a synthesized chunk: clamp out-of-range samples the vocoder can emit
     * (they hard-clip into a loud "pop"), and apply a ~4 ms fade at both edges so
     * joins between sentences don't click (edges are near-silence → inaudible).
     */
    private fun condition(s: FloatArray) {
        if (s.isEmpty()) return
        for (i in s.indices) {
            val v = s[i]
            if (v > 1f) s[i] = 1f else if (v < -1f) s[i] = -1f
        }
        val fade = (sampleRate / 250).coerceAtMost(s.size / 2) // ~4 ms
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            s[i] *= g
            s[s.size - 1 - i] *= g
        }
    }

    @Synchronized
    fun release() {
        tts?.let { old -> runCatching { onWorker { old.release() } } }
        tts = null
        loadedId = null
        loadedThreads = 0
    }
}
