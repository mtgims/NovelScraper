package com.novelscraper.app.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * The narrator's audio output on Android: an [AudioTrack] in streaming mode with
 * about three seconds of buffer, so a slow sentence (or fetching the next
 * chapter) can't leave a hole, and blocking writes pace the narrator to real time.
 */
class AudioTrackSink(sampleRate: Int) : PcmSink {

    private val track: AudioTrack = run {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        // 4 bytes per mono float frame.
        val bufSize = (sampleRate * 4 * 3).coerceAtLeast(minBuf)
        AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                // MUSIC, not SPEECH: some OEMs (e.g. MIUI) run SPEECH content through
                // a bandlimited voice-processing path ("old radio" sound); MUSIC keeps
                // the full-fidelity media path.
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).also { it.play() }
    }

    @Volatile private var closed = false
    private var written = 0L

    override fun write(pcm: FloatArray) {
        var off = 0
        while (off < pcm.size && !closed) {
            val n = track.write(pcm, off, pcm.size - off, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            off += n
        }
        written += pcm.size
    }

    /** AudioTrack's head wraps at 2^32 frames (about 50 hours at 24 kHz); unwrap
     *  it so a long listening session keeps counting up. */
    override val framesPlayed: Long
        get() {
            if (closed) return written
            val head = runCatching { track.playbackHeadPosition }.getOrDefault(0).toLong() and 0xFFFF_FFFFL
            while (head + wraps * WRAP < lastHead) wraps++
            lastHead = head + wraps * WRAP
            return lastHead
        }

    private var wraps = 0L
    private var lastHead = 0L

    override fun close() {
        closed = true
        runCatching { track.pause(); track.flush(); track.release() }
    }

    private companion object {
        const val WRAP = 1L shl 32
    }
}
