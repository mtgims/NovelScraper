package com.novelscraper.app.tts

import com.novelscraper.app.platform.Log

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

    /** How a model is built; replaced in tests. */
    internal var build: (TtsModels.Spec, String, Int) -> TtsModel = ::buildOfflineTts

    /** The threads the loaded model was asked for, to rebuild it the same way. */
    private var loadedThreads = 0

    /**
     * Load [modelId] ("kokoro" or a Piper voice id), rebuilding if a different one
     * is active. False if the model isn't present or native init throws (caller
     * falls back to device TTS). Plain CPU EP (XNNPACK measured slower here).
     */
    @Synchronized
    fun ensureLoaded(modelId: String, numThreads: Int = defaultThreads()): Boolean {
        if (tts != null && loadedId == modelId && !reload) return true
        reload = false
        if (tts != null) { runCatching { tts?.release() }; tts = null; loadedId = null }
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
        runCatching { tts?.release() }
        tts = built
        Log.i(TAG, "reloaded id=$id while narrating")
    }

    private fun tryBuild(modelId: String, threads: Int): TtsModel? {
        return try {
            build(TtsModels.spec(modelId), TtsModels.modelDir(modelId).absolutePath, threads)
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
        val engine = tts ?: return FloatArray(0)
        // Drop image placeholders — an illustration is its own "sentence" in the
        // shared split, but there's nothing to speak; treat it as silence.
        val t = text.replace(com.novelscraper.app.data.Sentences.OBJ, ' ').trim()
        if (t.isEmpty()) return FloatArray(0)
        val sid = speaker.coerceIn(0, (numSpeakers - 1).coerceAtLeast(0))
        return try {
            val t0 = System.nanoTime()
            val samples = engine.generate(t, sid, speed.coerceIn(0.5f, 2.5f))
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
        runCatching { tts?.release() }
        tts = null
        loadedId = null
        loadedThreads = 0
    }
}
