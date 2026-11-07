package com.novelscraper.app.net

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * How long to leave between requests to one source, learned from how it answers.
 *
 * A site that answers quickly is asked at the usual pace; one that takes three
 * seconds a page is given three seconds between pages, so a long download never
 * asks more of it than it can give. A site that says it is being asked too often
 * (429, or 503 with a Retry-After) gets the wait it asks for, or double the last
 * one when it names none, and a refusal never shortens the wait.
 *
 * Kept per source (plugin id), not per host: the download queue knows which
 * source a chapter comes from, and what hosts that source talks to is its own
 * business. Held in memory only: a site's mood today says little about next week.
 */
object SitePacing {
    /** The pause between requests to a source that answers quickly. */
    const val FLOOR_MS = 1_000L
    /** The most the app will wait between two requests, whatever a site asks. */
    const val MAX_MS = 120_000L

    private val delays = ConcurrentHashMap<String, Long>()

    /** How long to wait before asking [source] again. */
    fun delayFor(source: String?): Long = source?.let { delays[it] } ?: FLOOR_MS

    /** A request to [source] took [latencyMs]; [ok] is false if the site refused
     *  it for asking too often, saying how long to wait in [retryAfterMs]. */
    fun record(source: String, latencyMs: Long, ok: Boolean, retryAfterMs: Long? = null): Long =
        delays.merge(source, pace(FLOOR_MS, latencyMs, ok, retryAfterMs)) { current, _ ->
            pace(current, latencyMs, ok, retryAfterMs)
        }!!

    private fun pace(current: Long, latencyMs: Long, ok: Boolean, retryAfterMs: Long?): Long {
        // Halfway from the last wait toward how long the site took, and never
        // less than that: a site is not asked faster than it answers.
        var next = maxOf((current + latencyMs) / 2, latencyMs)
        if (!ok) next = maxOf(next, retryAfterMs ?: (current * 2), current)
        return next.coerceIn(FLOOR_MS, MAX_MS)
    }

    /** A Retry-After header in milliseconds from [nowMs]: whole seconds or an
     *  HTTP date. Null when missing or unreadable. */
    fun parseRetryAfter(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { return if (it >= 0) it * 1_000 else null }
        return runCatching {
            val at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            maxOf(at - nowMs, 0L)
        }.getOrNull()
    }

    internal fun reset() = delays.clear()
}
