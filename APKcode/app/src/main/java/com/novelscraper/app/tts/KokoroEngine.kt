package com.novelscraper.app.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * On-device neural TTS (Kokoro) via sherpa-onnx. The ~150 MB model package is
 * downloaded on first use ([KokoroDownloader]) into [modelDir]; this thin wrapper
 * builds the native [OfflineTts] from those files and synthesizes one span at a time.
 *
 * Not thread-safe: [generate] must be serialized by the caller (the native engine
 * is single-inference). [ensureLoaded]/[release] are synchronized.
 */
object KokoroEngine {
    // v1.0 = the multilingual model (American/British English, Spanish, French,
    // Italian, Hindi, Japanese, Portuguese, Chinese) — 53 voices. (v1.1 was
    // English+Chinese only, with 100 Chinese voices.)
    const val MODEL_DIR_NAME = "kokoro-int8-multi-lang-v1_0"
    private const val TAG = "KokoroEngine"

    // Files the engine needs to be present before it can load.
    private val REQUIRED = listOf(
        "model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt",
    )

    @Volatile private var tts: OfflineTts? = null

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    /** True once every file the engine needs is present on disk. */
    fun isModelReady(context: Context): Boolean {
        val d = modelDir(context)
        return REQUIRED.all { File(d, it).exists() } && File(d, "espeak-ng-data").isDirectory
    }

    /** Delete any previously-downloaded Kokoro model that isn't the current one
     *  (e.g. an old v1.1 install), reclaiming its ~200 MB. */
    fun cleanupOtherModels(context: Context) {
        context.filesDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("kokoro-") && it.name != MODEL_DIR_NAME }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    val sampleRate: Int get() = tts?.sampleRate() ?: 24000
    val numSpeakers: Int get() = tts?.numSpeakers() ?: 0

    /**
     * Lazily build the native engine from the downloaded files. Returns false if the
     * model isn't present or native init throws (caller should fall back to device TTS).
     */
    @Synchronized
    fun ensureLoaded(context: Context, numThreads: Int = 4): Boolean {
        if (tts != null) return true
        if (!isModelReady(context)) return false
        return try {
            val d = modelDir(context).absolutePath
            val kokoro = OfflineTtsKokoroModelConfig().apply {
                model = "$d/model.int8.onnx"
                voices = "$d/voices.bin"
                tokens = "$d/tokens.txt"
                dataDir = "$d/espeak-ng-data"
                lexicon = "$d/lexicon-us-en.txt"
                lang = "en"
            }
            val modelConfig = OfflineTtsModelConfig().apply {
                this.kokoro = kokoro
                this.numThreads = numThreads.coerceIn(1, 8)
                provider = "cpu"
                debug = false
            }
            // assetManager = null -> paths are treated as filesystem paths.
            tts = OfflineTts(null, OfflineTtsConfig().apply { this.model = modelConfig })
            Log.i(TAG, "Kokoro loaded: sr=${sampleRate} speakers=${numSpeakers}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Kokoro init failed", t)
            tts = null
            false
        }
    }

    /**
     * Synthesize one text span to mono float PCM at [sampleRate]. Empty on failure.
     * `@Synchronized` (same monitor as [ensureLoaded]/[release]) so generation is
     * single-flight — ONNX Runtime is not thread-safe, and this also guarantees
     * [release] cannot free the engine while a generate is in flight (use-after-free).
     */
    @Synchronized
    fun generate(text: String, speaker: Int, speed: Float): FloatArray {
        val engine = tts ?: return FloatArray(0)
        val t = text.trim()
        if (t.isEmpty()) return FloatArray(0)
        val sid = speaker.coerceIn(0, (numSpeakers - 1).coerceAtLeast(0))
        return try {
            engine.generate(t, sid, speed.coerceIn(0.5f, 2.5f)).samples
        } catch (t2: Throwable) {
            Log.e(TAG, "generate failed", t2)
            FloatArray(0)
        }
    }

    @Synchronized
    fun release() {
        runCatching { tts?.release() }
        tts = null
    }
}
