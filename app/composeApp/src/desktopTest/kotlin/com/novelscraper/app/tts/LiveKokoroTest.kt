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

    /**
     * The same passage read plainly and with expressive reading, through the
     * real narrator, to listen to side by side: with `-PliveTtsOut=<file.wav>`
     * they land at `<file>-plain.wav` and `<file>-expressive.wav`.
     */
    @Test
    fun readsDialogueExpressively() {
        if (System.getProperty("live.tts") != "true") return
        if (!TtsModels.isModelReady(TtsModels.KOKORO)) return println("no Kokoro model: run speaksASentence first")
        com.novelscraper.app.data.ReaderPrefs.init()
        val prefs = com.novelscraper.app.data.ReaderPrefs
        prefs.setTtsEngine(prefs.ENGINE_KOKORO)
        prefs.setKokoroSpeaker(KokoroVoices.all.first { it.code == "af_heart" }.id)
        prefs.setKokoroDialogueSpeaker(-1)
        prefs.setTtsRate(1f)
        prefs.setTtsAutoNext(false)
        val passage = "<p>\"V-Violet. You-You're alive.\"</p>" +
            "<p>\"Violet, that-that hurts. You need... you need to lift the beams, please,\" he said.</p>" +
            "<p>\"Stay here,\" she whispered. \"Don't move.\"</p>" +
            "<p>\"Get out of my house!\" he shouted.</p><p>\"STOP RIGHT THERE!\"</p>" +
            "<p>The rain had not let up since noon. Klein stood at the window, watching the lamps.</p>" +
            "<p>\"You're late,\" said a voice behind him. \"Again.\"</p>" +
            "<p>He didn't turn around. \"The roads were flooded.</p>" +
            "<p>\"They always are,\" she said. \"Sit down!\"</p><p>***</p>" +
            "<p>Morning came slowly, grey and cold, and the city woke to the sound of bells...</p>"
        val chapter = com.novelscraper.app.data.ChapterRead(
            position = 1, number = "1", title = "Test", content = passage, has_prev = false, has_next = false,
        )
        val out = System.getProperty("live.tts.out")?.takeIf { it.isNotBlank() }?.removeSuffix(".wav")
        for (on in listOf(false, true)) {
            prefs.setTtsExpressive(on)
            val pcm = java.io.ByteArrayOutputStream()
            var done = false
            var frames = 0L
            val narrator = NeuralNarrator(
                openSink = {
                    object : PcmSink {
                        override fun write(pcm0: FloatArray) {
                            val b = ByteBuffer.allocate(pcm0.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                            pcm0.forEach { b.putFloat(it) }
                            synchronized(pcm) { pcm.write(b.array()) }
                            frames += pcm0.size
                        }
                        override val framesPlayed get() = frames
                        override fun close() { done = true }
                    }
                },
                fetchChapter = { _, _ -> chapter },
                markRead = { _, _ -> },
                director = ExpressiveReading,
            )
            val t0 = System.currentTimeMillis()
            narrator.play(1, 1, "Test", 0)
            Thread.sleep(500)
            while (TtsController.state.value.active && System.currentTimeMillis() - t0 < 180_000) Thread.sleep(50)
            narrator.stop()
            val bytes = synchronized(pcm) { pcm.toByteArray() }
            val floats = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            val name = if (on) "expressive" else "plain"
            println("$name: ${"%.1f".format(floats.size.toDouble() / KokoroEngine.sampleRate)}s of audio")
            out?.let { writeWav(File("$it-$name.wav"), floats, KokoroEngine.sampleRate); println("written to $it-$name.wav") }
            assertTrue(floats.isNotEmpty(), "nothing came out")
        }
        prefs.setTtsExpressive(false)

        // How loud the narrator and the dialogue voice are, as the model gives
        // them and once directed speech has levelled them.
        val line = "The lamps were lit early that evening, and the rain had not let up."
        val narrator = prefs.kokoroSpeaker.value
        val dialogue = ExpressiveReading.dialogueSpeaker(1, prefs.ENGINE_KOKORO, narrator)
        val db = { a: Float, b: Float -> "%.1f dB".format(20 * kotlin.math.log10(a / b)) }
        val sr = KokoroEngine.sampleRate
        val rawN = speechRms(KokoroEngine.generate(line, narrator, 1f), sr)
        val rawD = speechRms(KokoroEngine.generate(line, dialogue, 1f), sr)
        val speech = DirectedSpeech(KokoroVoice, ExpressiveReading, 1)
        val n = speechRms(speech.speak(Direction(listOf(Piece(line, false)), 0), prefs.ENGINE_KOKORO, narrator, 1f), sr)
        val d = speechRms(speech.speak(Direction(listOf(Piece(line, true)), 0), prefs.ENGINE_KOKORO, narrator, 1f), sr)
        println("dialogue against narrator: ${db(rawD, rawN)} as given, ${db(d, n)} levelled")

        // A whisper and a shout against the same line said plainly.
        val plainLine = speechRms(speech.speak(Direction(listOf(Piece(line, true)), 0), prefs.ENGINE_KOKORO, narrator, 1f), sr)
        for ((name, gain) in listOf("whisper" to ExpressiveReading.WHISPER_GAIN, "shout" to ExpressiveReading.SHOUT_GAIN)) {
            val r = speechRms(speech.speak(Direction(listOf(Piece(line, true, gain = gain)), 0), prefs.ENGINE_KOKORO, narrator, 1f), sr)
            println("$name: ${db(r, plainLine)}")
        }

        // Whether capitals are spelled out: the same words in capitals and in
        // lower case, and how long each takes to say.
        for (t in listOf("STOP RIGHT THERE!", "stop right there!", "HELP", "help")) {
            println("\"$t\": ${"%.2f".format(trimSilence(KokoroEngine.generate(t, narrator, 1f), sr).pcm.size.toDouble() / sr)}s")
        }
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
