package com.novelscraper.app.net

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How long the app leaves between requests to one source. */
class SitePacingTest {

    @AfterTest fun forget() = SitePacing.reset()

    @Test fun aSourceNeverHeardFromGetsTheFloor() {
        assertEquals(SitePacing.FLOOR_MS, SitePacing.delayFor("new"))
        assertEquals(SitePacing.FLOOR_MS, SitePacing.delayFor(null))
    }

    @Test fun aQuickSiteStaysAtTheFloor() {
        repeat(20) { SitePacing.record("quick", latencyMs = 200, ok = true) }
        assertEquals(SitePacing.FLOOR_MS, SitePacing.delayFor("quick"))
    }

    @Test fun aSlowSiteIsGivenAsLongAsItTakes() {
        SitePacing.record("slow", latencyMs = 3_000, ok = true)
        assertEquals(3_000, SitePacing.delayFor("slow"))
        // Then it speeds up again, halfway each time.
        SitePacing.record("slow", latencyMs = 1_000, ok = true)
        assertEquals(2_000, SitePacing.delayFor("slow"))
    }

    @Test fun beingRefusedDoublesTheWait() {
        SitePacing.record("busy", latencyMs = 100, ok = false)
        assertEquals(2 * SitePacing.FLOOR_MS, SitePacing.delayFor("busy"))
        SitePacing.record("busy", latencyMs = 100, ok = false)
        assertEquals(4 * SitePacing.FLOOR_MS, SitePacing.delayFor("busy"))
    }

    @Test fun theWaitTheSiteAsksForIsTheWaitGiven() {
        SitePacing.record("polite", latencyMs = 100, ok = false, retryAfterMs = 7_000)
        assertEquals(7_000, SitePacing.delayFor("polite"))
    }

    /** A refusal that asks for less than the app already waits does not speed it up. */
    @Test fun aRefusalNeverShortensTheWait() {
        SitePacing.record("s", latencyMs = 9_000, ok = true)
        SitePacing.record("s", latencyMs = 100, ok = false, retryAfterMs = 1_000)
        assertTrue(SitePacing.delayFor("s") >= 9_000, "was ${SitePacing.delayFor("s")}")
    }

    @Test fun theWaitHasACeiling() {
        SitePacing.record("hostile", latencyMs = 100, ok = false, retryAfterMs = 3_600_000)
        assertEquals(SitePacing.MAX_MS, SitePacing.delayFor("hostile"))
    }

    @Test fun sourcesArePacedApart() {
        SitePacing.record("a", latencyMs = 100, ok = false, retryAfterMs = 30_000)
        assertEquals(SitePacing.FLOOR_MS, SitePacing.delayFor("b"))
    }

    @Test fun retryAfterIsReadInSecondsOrAsADate() {
        val now = 1_800_000_000_000L
        assertEquals(120_000, SitePacing.parseRetryAfter("120", now))
        assertEquals(0, SitePacing.parseRetryAfter("0", now))
        val inAMinute = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now + 60_000), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(60_000, SitePacing.parseRetryAfter(inAMinute, now))
        // A date already gone means no wait, not a negative one.
        val past = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now - 60_000), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(0, SitePacing.parseRetryAfter(past, now))
    }

    @Test fun anUnreadableRetryAfterIsIgnored() {
        assertNull(SitePacing.parseRetryAfter(null))
        assertNull(SitePacing.parseRetryAfter(""))
        assertNull(SitePacing.parseRetryAfter("soon"))
        assertNull(SitePacing.parseRetryAfter("-5"))
    }
}
