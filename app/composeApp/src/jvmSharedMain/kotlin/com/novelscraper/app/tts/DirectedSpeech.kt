package com.novelscraper.app.tts

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Speaks directed sentences ([Direction]) with a [voice]: each piece in its
 * speaker's voice, the silence the model leaves around every piece trimmed so a
 * pause is the pause asked for, and every voice brought to the narrator's
 * loudness. One per run of narration (or per exported file): it learns each
 * voice's level as it goes.
 *
 * Pauses are measured voice to voice. A trimmed piece keeps [LEAD_PAD_MS] of
 * lead-in and [TAIL_PAD_MS] of tail, so soft starts and endings survive, and
 * what is inserted between two pieces is the pause less the pads already there.
 */
class DirectedSpeech(
    private val voice: NarratorVoice,
    private val director: NarrationDirector,
    private val bookId: Int,
) {
    /** Each speaker's speech level, a moving average over the pieces heard. */
    private val level = HashMap<Int, Float>()

    private class Part(val pcm: FloatArray, val lead: Int, val tail: Int, val pauseMs: Int)

    fun speak(direction: Direction, engine: String, narrator: Int, rate: Float): FloatArray {
        val sr = voice.sampleRate
        val speakers = HashMap<Role, Int>()
        val samples = { ms: Float -> (ms * sr / 1000).toInt() }
        val parts = direction.pieces.map { p ->
            val speaker = speakers.getOrPut(p.role) { director.speakerFor(bookId, engine, narrator, p.role) }
            val raw = voice.generate(p.text, speaker, (rate * p.speedFactor).coerceIn(0.5f, 2.5f))
            val said = if (p.onset > 0f) Trimmed(onsetOf(raw, p.onset, sr), 0, 0) else trimSilence(raw, sr)
            // A stutter's fragment is too short to say how loud a voice is.
            if (p.onset == 0f) learn(speaker, speechRms(said.pcm, sr))
            Part(scaled(said.pcm, gainFor(speaker, narrator) * p.gain), said.lead, said.tail, p.pauseAfterMs)
        }
        // The gap after each part, less the pads on either side of it. After the
        // last, the next sentence is assumed to start with a full lead-in.
        val gaps = parts.mapIndexed { k, part ->
            val pauseMs = part.pauseMs + if (k == parts.lastIndex) direction.pauseAfterMs else 0
            if (pauseMs <= 0) return@mapIndexed 0
            val nextLead = parts.getOrNull(k + 1)?.lead ?: samples(LEAD_PAD_MS.toFloat())
            (samples(pauseMs / rate) - part.tail - nextLead).coerceAtLeast(0)
        }
        // Nothing to say (a scene break): the whole pause.
        val tailSilence = if (parts.isEmpty()) samples(direction.pauseAfterMs / rate).coerceAtLeast(0) else 0
        val out = FloatArray(parts.sumOf { it.pcm.size } + gaps.sum() + tailSilence)
        var at = 0
        for ((k, part) in parts.withIndex()) {
            part.pcm.copyInto(out, at)
            at += part.pcm.size + gaps[k]
        }
        return out
    }

    private fun learn(speaker: Int, rms: Float) {
        if (rms <= 0f) return
        level[speaker] = level[speaker]?.let { it + LEVEL_ALPHA * (rms - it) } ?: rms
    }

    /** What brings [speaker] to the narrator's level; the narrator itself is left as it is. */
    private fun gainFor(speaker: Int, narrator: Int): Float {
        if (speaker == narrator) return 1f
        val target = level[narrator] ?: return 1f
        val own = level[speaker]?.takeIf { it > 0f } ?: return 1f
        return (target / own).coerceIn(MIN_GAIN, MAX_GAIN)
    }

    internal companion object {
        const val LEAD_PAD_MS = 15
        const val TAIL_PAD_MS = 40
        const val FADE_IN_MS = 5
        const val FADE_OUT_MS = 10
        const val LEVEL_ALPHA = 0.3f
        const val MIN_GAIN = 0.5f
        const val MAX_GAIN = 2.0f
        const val PEAK = 0.97f
    }
}

/** Audio with its silence trimmed, and how much lead-in and tail it kept. */
internal class Trimmed(val pcm: FloatArray, val lead: Int, val tail: Int)

/**
 * [pcm] without the silence around the voice, keeping up to
 * [DirectedSpeech.LEAD_PAD_MS] before it and [DirectedSpeech.TAIL_PAD_MS] after,
 * faded in and out within those pads so the cut never clicks. Silence is
 * anything under 1.5% of the loudest sample, so quiet voices trim as well as
 * loud ones.
 */
internal fun trimSilence(pcm: FloatArray, sampleRate: Int): Trimmed {
    val peak = pcm.maxOfOrNull { abs(it) } ?: 0f
    if (peak < 1e-4f) return Trimmed(FloatArray(0), 0, 0)
    val quiet = maxOf(peak * 0.015f, 0.002f)
    val first = pcm.indexOfFirst { abs(it) > quiet }
    val last = pcm.indexOfLast { abs(it) > quiet }
    val lead = minOf(first, sampleRate * DirectedSpeech.LEAD_PAD_MS / 1000)
    val tail = minOf(pcm.size - 1 - last, sampleRate * DirectedSpeech.TAIL_PAD_MS / 1000)
    val out = pcm.copyOfRange(first - lead, last + 1 + tail)
    val fadeIn = minOf(lead, sampleRate * DirectedSpeech.FADE_IN_MS / 1000)
    for (i in 0 until fadeIn) out[i] *= i.toFloat() / fadeIn
    val fadeOut = minOf(tail, sampleRate * DirectedSpeech.FADE_OUT_MS / 1000)
    for (i in 0 until fadeOut) out[out.size - 1 - i] *= i.toFloat() / fadeOut
    return Trimmed(out, lead, tail)
}

/**
 * How loud the speech in [pcm] is: the RMS of its 10 ms frames that carry
 * speech (above a tenth of the loudest frame), so pauses between words don't
 * count. Zero for silence.
 */
internal fun speechRms(pcm: FloatArray, sampleRate: Int): Float {
    val frame = maxOf(1, sampleRate / 100)
    val powers = (pcm.indices step frame).map { start ->
        val end = minOf(pcm.size, start + frame)
        var sum = 0.0
        for (i in start until end) sum += pcm[i].toDouble() * pcm[i]
        sum / (end - start)
    }
    val loudest = powers.maxOrNull() ?: return 0f
    if (loudest <= 0.0) return 0f
    // A tenth of the loudest frame's RMS is a hundredth of its power.
    val speech = powers.filter { it > loudest / 100 }
    return sqrt(speech.average()).toFloat()
}

/** [pcm] at [gain], a boost held back so no sample passes [DirectedSpeech.PEAK]. */
internal fun scaled(pcm: FloatArray, gain: Float): FloatArray {
    if (gain == 1f || pcm.isEmpty()) return pcm
    val peak = pcm.maxOf { abs(it) }
    val g = if (gain > 1f && peak > 0f) minOf(gain, maxOf(1f, DirectedSpeech.PEAK / peak)) else gain
    if (g == 1f) return pcm
    return FloatArray(pcm.size) { pcm[it] * g }
}

/**
 * The opening [share] of the voiced part of [pcm] (the silence a voice leaves
 * around a word is not counted), between 70 and 250 ms, faded out so the cut
 * doesn't click.
 */
internal fun onsetOf(pcm: FloatArray, share: Float, sampleRate: Int): FloatArray {
    val loud = 0.02f
    val first = pcm.indexOfFirst { abs(it) > loud }
    if (first < 0) return FloatArray(0)
    val last = pcm.indexOfLast { abs(it) > loud }
    val voiced = last - first + 1
    val n = (voiced * share).toInt()
        .coerceIn(sampleRate * 70 / 1000, sampleRate * 250 / 1000)
        .coerceAtMost(voiced)
    val out = pcm.copyOfRange(first, first + n)
    val fade = minOf(n, sampleRate * 12 / 1000)
    for (i in 0 until fade) out[n - 1 - i] *= i.toFloat() / fade
    return out
}
