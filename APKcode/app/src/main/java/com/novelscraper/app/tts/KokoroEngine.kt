package com.novelscraper.app.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * On-device neural TTS via sherpa-onnx, supporting two downloadable engines:
 *  - "kokoro" — Kokoro 82M multi-lang (53 voices, most natural; ~1x real time on
 *    a fast phone CPU), and
 *  - "piper"  — a Piper/VITS voice (single speaker, much lighter → several times
 *    real time; less expressive but no buffering).
 *
 * Only one is loaded at a time (switching rebuilds). Not thread-safe: generate()
 * is serialized with load/release via the object monitor.
 */
object KokoroEngine {
    private const val TAG = "KokoroEngine"
    const val KOKORO = "kokoro"
    const val PIPER = "piper"

    private const val BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    private data class Spec(
        val dir: String,
        val url: String,
        val kind: String,          // "kokoro" | "vits"
        val onnx: String,
        val required: List<String>,
    )

    private val SPECS = mapOf(
        KOKORO to Spec(
            dir = "kokoro-int8-multi-lang-v1_0",
            url = "$BASE/kokoro-int8-multi-lang-v1_0.tar.bz2",
            kind = "kokoro",
            onnx = "model.int8.onnx",
            required = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt"),
        ),
        PIPER to Spec(
            dir = "vits-piper-en_US-amy-medium",
            url = "$BASE/vits-piper-en_US-amy-medium.tar.bz2",
            kind = "vits",
            onnx = "en_US-amy-medium.onnx",
            required = listOf("en_US-amy-medium.onnx", "tokens.txt"),
        ),
    )

    private fun spec(engine: String): Spec = SPECS[engine] ?: SPECS.getValue(KOKORO)

    fun modelDir(context: Context, engine: String): File =
        File(context.filesDir, spec(engine).dir)

    fun downloadUrl(engine: String): String = spec(engine).url

    /** True once every file the engine needs is present on disk. */
    fun isModelReady(context: Context, engine: String): Boolean {
        val s = spec(engine)
        val d = File(context.filesDir, s.dir)
        return s.required.all { File(d, it).exists() } && File(d, "espeak-ng-data").isDirectory
    }

    /** Delete previously-downloaded models that aren't one of the current two
     *  (e.g. an old Kokoro v1.1), reclaiming space; keeps both current engines. */
    fun cleanupOtherModels(context: Context) {
        val keep = SPECS.values.map { it.dir }.toSet()
        context.filesDir.listFiles()
            ?.filter { it.isDirectory && (it.name.startsWith("kokoro-") || it.name.startsWith("vits-")) && it.name !in keep }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var loadedEngine: String? = null

    val sampleRate: Int get() = tts?.sampleRate() ?: 24000
    val numSpeakers: Int get() = tts?.numSpeakers() ?: 0

    /**
     * Cap synthesis threads at 4. On big.LITTLE phones (e.g. Dimensity 8400 =
     * 4 fast cores @3.0-3.25 GHz + 4 @2.1 GHz) an ONNX op finishes only when its
     * slowest thread does, so spilling onto the slow cores makes generation
     * *slower*. 4 keeps work on the fast cores.
     */
    private fun defaultThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /**
     * Load [engine] ("kokoro"/"piper"), rebuilding if a different one is active.
     * Returns false if the model isn't present or native init throws (caller falls
     * back to device TTS). Plain CPU EP (XNNPACK measured slower for these models).
     */
    @Synchronized
    fun ensureLoaded(context: Context, engine: String, numThreads: Int = defaultThreads()): Boolean {
        if (tts != null && loadedEngine == engine) return true
        if (tts != null) { runCatching { tts?.release() }; tts = null; loadedEngine = null }
        if (!isModelReady(context, engine)) return false
        val threads = numThreads.coerceIn(1, 8)
        val built = tryBuild(context, engine, threads) ?: return false
        tts = built
        loadedEngine = engine
        Log.i(TAG, "loaded engine=$engine threads=$threads sr=$sampleRate speakers=$numSpeakers")
        return true
    }

    private fun tryBuild(context: Context, engine: String, threads: Int): OfflineTts? {
        return try {
            val s = spec(engine)
            val d = File(context.filesDir, s.dir).absolutePath
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
            Log.w(TAG, "build failed (engine=$engine): ${t.message}")
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
        val t = text.trim()
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
        loadedEngine = null
    }
}
