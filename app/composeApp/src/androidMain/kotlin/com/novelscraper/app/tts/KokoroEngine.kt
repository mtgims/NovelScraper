package com.novelscraper.app.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * On-device neural TTS via sherpa-onnx, running one of the models in
 * [TtsModels] (Kokoro or a Piper voice).
 *
 * Only one model is loaded at a time (switching rebuilds). Not thread-safe:
 * generate() is serialized with load/release via the object monitor.
 */
object KokoroEngine {
    private const val TAG = "KokoroEngine"
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var loadedId: String? = null

    val sampleRate: Int get() = tts?.sampleRate() ?: 24000
    val numSpeakers: Int get() = tts?.numSpeakers() ?: 0

    /**
     * Cap synthesis threads at 4. On big.LITTLE phones an ONNX op finishes only
     * when its slowest thread does, so spilling onto slow cores makes generation
     * *slower*. 4 keeps work on the fast cores.
     */
    private fun defaultThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /**
     * Load [modelId] ("kokoro" or a Piper voice id), rebuilding if a different one
     * is active. False if the model isn't present or native init throws (caller
     * falls back to device TTS). Plain CPU EP (XNNPACK measured slower here).
     */
    @Synchronized
    fun ensureLoaded(modelId: String, numThreads: Int = defaultThreads()): Boolean {
        if (tts != null && loadedId == modelId) return true
        if (tts != null) { runCatching { tts?.release() }; tts = null; loadedId = null }
        if (!TtsModels.isModelReady(modelId)) return false
        val threads = numThreads.coerceIn(1, 8)
        val built = tryBuild(modelId, threads) ?: return false
        tts = built
        loadedId = modelId
        Log.i(TAG, "loaded id=$modelId threads=$threads sr=$sampleRate speakers=$numSpeakers")
        return true
    }

    private fun tryBuild(modelId: String, threads: Int): OfflineTts? {
        return try {
            val s = TtsModels.spec(modelId)
            val d = TtsModels.modelDir(modelId).absolutePath
            val modelConfig = OfflineTtsModelConfig().apply {
                if (s.kind == "vits") {
                    this.vits = OfflineTtsVitsModelConfig().apply {
                        model = "$d/${s.onnx}"
                        tokens = "$d/tokens.txt"
                        dataDir = "$d/espeak-ng-data"
                    }
                } else {
                    this.kokoro = OfflineTtsKokoroModelConfig().apply {
                        model = "$d/${s.onnx}"
                        voices = "$d/voices.bin"
                        tokens = "$d/tokens.txt"
                        dataDir = "$d/espeak-ng-data"
                        lexicon = "$d/lexicon-us-en.txt"
                        lang = "en"
                    }
                }
                this.numThreads = threads
                provider = "cpu"
                debug = false
            }
            OfflineTts(null, OfflineTtsConfig().apply { this.model = modelConfig })
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
        val engine = tts ?: return FloatArray(0)
        // Drop image placeholders — an illustration is its own "sentence" in the
        // shared split, but there's nothing to speak; treat it as silence.
        val t = text.replace(com.novelscraper.app.data.Sentences.OBJ, ' ').trim()
        if (t.isEmpty()) return FloatArray(0)
        val sid = speaker.coerceIn(0, (numSpeakers - 1).coerceAtLeast(0))
        return try {
            val t0 = System.nanoTime()
            val out = engine.generate(t, sid, speed.coerceIn(0.5f, 2.5f))
            val samples = out.samples
            condition(samples)
            val inferMs = (System.nanoTime() - t0) / 1_000_000.0
            val audioSec = samples.size.toDouble() / out.sampleRate.coerceAtLeast(1)
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
    }
}
