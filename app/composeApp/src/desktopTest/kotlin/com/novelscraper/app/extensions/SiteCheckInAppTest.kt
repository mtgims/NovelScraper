package com.novelscraper.app.extensions

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A check the reader has open in the app's own web view. */
class SiteCheckInAppTest {
    @AfterTest fun clear() { SiteChecks.endInApp(); SiteChecks.dismiss() }

    @Test fun nothingElseAsksWhileACheckIsOpen() {
        SiteChecks.needed("https://site.example/novel/1")
        SiteChecks.beginInApp("https://site.example/novel/1")
        assertNull(SiteChecks.pending.value, "the prompt goes once its check is on screen")
        assertEquals("https://site.example/novel/1", SiteChecks.inApp.value)

        SiteChecks.needed("https://site.example/novel/2")
        assertNull(SiteChecks.pending.value, "a source asking again meanwhile puts no prompt over it")
    }

    @Test fun closingTheCheckAsksNothingAgain() {
        SiteChecks.beginInApp("https://site.example/novel/1")
        SiteChecks.needed("https://site.example/novel/2")
        SiteChecks.endInApp()
        assertNull(SiteChecks.inApp.value)
        assertNull(SiteChecks.pending.value, "closed, passed or not, with nothing waiting behind it")

        SiteChecks.needed("https://site.example/novel/3")
        assertEquals("https://site.example/novel/3", SiteChecks.pending.value, "and the next check asks as before")
    }
}
