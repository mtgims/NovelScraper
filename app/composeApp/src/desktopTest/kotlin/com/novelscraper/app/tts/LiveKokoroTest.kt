package com.novelscraper.app.tts

import okhttp3.OkHttpClient
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fetches the Kokoro model this platform is set up to use, speaks a sentence with
 * it and reports what came out. Off by default (a few hundred megabytes and a
 * minute of processor): run with
 * `./gradlew :composeApp:desktopTest --tests "*LiveKokoroTest*" -PliveTts=true`.
 */
class LiveKokoroTest {

    @Test
    fun speaksASentence() {
        if (System.getProperty("live.tts") != "true") return
        val model = TtsModels.spec(TtsModels.KOKORO)
        println("model: ${model.dir} (${model.onnx})")
        if (!TtsModels.isModelReady(TtsModels.KOKORO)) {
            println("fetching it…")
            var last = -1
            KokoroDownloader.download(OkHttpClient(), TtsModels.KOKORO) { p ->
                when (p) {
                    is KokoroDownloader.Progress.Downloading -> {
                        val pct = (p.bytes * 100 / p.total.coerceAtLeast(1)).toInt()
                        if (pct / 10 != last) { last = pct / 10; println("  $pct%") }
                    }
                    is KokoroDownloader.Progress.Failed -> println("  failed: ${p.message}")
                    else -> println("  $p")
                }
            }
        }
        assertTrue(TtsModels.isModelReady(TtsModels.KOKORO), "the model isn't there")
        assertTrue(KokoroEngine.ensureLoaded(TtsModels.KOKORO), "the model wouldn't load")

        val text = "The lamps were lit early that evening, and the rain had not let up since noon."
        val t0 = System.nanoTime()
        val pcm = KokoroEngine.generate(text, speaker = 0, speed = 1.0f)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val rate = KokoroEngine.sampleRate
        val seconds = pcm.size.toDouble() / rate
        val peak = pcm.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        val clipped = pcm.count { kotlin.math.abs(it) >= 0.999f }
        println("sample rate: $rate")
        println("audio: ${"%.2f".format(seconds)}s in ${ms}ms (RTF ${"%.2f".format(ms / 1000.0 / seconds)})")
        println("peak: ${"%.3f".format(peak)}, samples at full scale: $clipped")

        System.getProperty("live.tts.out")?.takeIf { it.isNotBlank() }?.let { path ->
            writeWav(File(path), pcm, rate)
            println("written to $path")
        }
        assertTrue(seconds > 1.0, "nothing came out")
        KokoroEngine.release()
    }

    private fun writeWav(file: File, pcm: FloatArray, rate: Int) {
        val data = ByteArray(pcm.size * 2)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (v in pcm) buf.putShort((v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(data.size)
        file.writeBytes(header.array() + data)
    }
}
