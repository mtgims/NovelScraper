package com.novelscraper.app.extensions

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    @Test fun aPassIsForItsOwnSite() {
        assertTrue(SiteChecks.sameSite("https://www.site.example/a", "https://site.example/b"))
        assertTrue(SiteChecks.sameSite("https://cdn.site.example/x", "https://site.example/"))
        assertFalse(SiteChecks.sameSite("https://site.example/", "https://other.example/"))
        assertFalse(SiteChecks.sameSite("https://notsite.example/", "https://site.example/"), "a longer name isn't a subdomain")
        assertFalse(SiteChecks.sameSite(null, "https://site.example/"))
    }

    @Test fun aPassIsHeardWithItsAddress() = kotlinx.coroutines.runBlocking {
        val heard = kotlinx.coroutines.CompletableDeferred<String>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { heard.complete(SiteChecks.passed.first()) }
        SiteChecks.passed("https://site.example/novel/1")
        assertEquals("https://site.example/novel/1", kotlinx.coroutines.withTimeout(2000) { heard.await() })
        job.cancel()
    }
}
