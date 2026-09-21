package com.novelscraper.app.tts

import java.io.File
import java.io.RandomAccessFile
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Narration on desktop: the shared [NeuralNarrator] playing through Java Sound. */
object DesktopTtsPlayer : TtsController.Player by narrator {
    fun release() = narrator.release()
}

// NOVELSCRAPER_AUDIO_FILE=<path.wav> sends narration to a file instead of the
// speakers (for testing without sound; the file plays out at real-time pace).
private val narrator = NeuralNarrator(
    openSink = System.getenv("NOVELSCRAPER_AUDIO_FILE")?.takeIf { it.isNotBlank() }
        ?.let { path -> { rate: Int -> WavFileSink(File(path), rate) } }
        ?: ::JavaSoundSink,
)

/**
 * A [PcmSink] on the default audio output (PipeWire/PulseAudio/ALSA via Java
 * Sound), 16-bit mono at the model's sample rate, with about a second of buffer so
 * a slow sentence doesn't cause a gap.
 */
private class JavaSoundSink(sampleRate: Int) : PcmSink {
    private val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).apply {
        open(format, sampleRate * 2)  // ~1 s: 2 bytes per mono frame
        start()
    }
    @Volatile private var closed = false

    override fun write(pcm: FloatArray) {
        val bytes = pcm16(pcm)
        var off = 0
        while (off < bytes.size && !closed) {
            val n = line.write(bytes, off, bytes.size - off)  // blocks while the buffer is full
            if (n <= 0) break
            off += n
        }
    }

    override val framesPlayed: Long get() = line.longFramePosition

    override fun close() {
        closed = true
        line.stop()
        line.flush()
        line.close()
    }
}

/** 16-bit PCM from float samples, little-endian. */
private fun pcm16(pcm: FloatArray): ByteArray {
    val bytes = ByteArray(pcm.size * 2)
    for (i in pcm.indices) {
        val v = (pcm[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
        bytes[2 * i] = v.toByte()
        bytes[2 * i + 1] = (v shr 8).toByte()
    }
    return bytes
}

/**
 * A [PcmSink] that appends to a WAV file and "plays" in real time: the playback
 * head advances with the clock and writes block once a second is buffered, like
 * the audio device would. Each chapter (sink) appends to the same file.
 */
private class WavFileSink(private val file: File, private val sampleRate: Int) : PcmSink {
    private var written = 0L
    private var startNs = 0L
    @Volatile private var closed = false

    override val framesPlayed: Long
        get() = if (startNs == 0L) 0L
                else minOf(written, (System.nanoTime() - startNs) * sampleRate / 1_000_000_000L)

    override fun write(pcm: FloatArray) {
        if (startNs == 0L) startNs = System.nanoTime()
        synchronized(lock) { append(pcm16(pcm)) }
        written += pcm.size
        while (!closed && written - framesPlayed > sampleRate) Thread.sleep(20)
    }

    override fun close() { closed = true }

    private fun append(bytes: ByteArray) {
        RandomAccessFile(file, "rw").use { f ->
            if (f.length() < 44) { f.setLength(0); f.write(header(0)) }
            f.seek(f.length())
            f.write(bytes)
            val data = f.length() - 44
            f.seek(0)
            f.write(header(data))
        }
    }

    private fun header(dataBytes: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        b.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }

    private companion object { val lock = Any() }
}
