package com.novelscraper.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Telling a site's challenge page apart from a chapter that merely says so. */
class BrowserCheckTest {

    @Test fun theInterstitialIsRecognised() {
        assertTrue(looksLikeBrowserCheck("<html><head><title>Just a moment...</title></head><body></body></html>"))
    }

    @Test fun challengeMarkersAreRecognisedAnywhere() {
        assertTrue(looksLikeBrowserCheck("<html><body><script>window.cf_chl_opt={}</script></body></html>"))
        assertTrue(looksLikeBrowserCheck("<html><body>check_are_you_bot</body></html>"))
    }

    /** The reason this changed: ordinary dialogue is not a challenge page. */
    @Test fun aChapterSayingItInProseIsNot() {
        val chapter = """
            <html><head><title>Chapter 12 - The Long Road</title></head>
            <body><p>&ldquo;Just a moment,&rdquo; she said, and closed the door.</p></body></html>
        """.trimIndent()
        assertFalse(looksLikeBrowserCheck(chapter), "a novel may say it without being a challenge")
    }

    @Test fun anOrdinaryPageIsNot() {
        assertFalse(looksLikeBrowserCheck("<html><head><title>Chapter 1</title></head><body><p>Text.</p></body></html>"))
    }

    /** Cloudflare's interstitial, as it is served, with the kind of check it is. */
    private fun interstitial(cType: String) = """
        <!DOCTYPE html><html lang="en-US"><head><title>Just a moment...</title></head>
        <body><div class="main-wrapper"></div>
        <script>(function(){window._cf_chl_opt = {cvId: '3',cZone: 'www.example.com',cType: '$cType',
        cRay: '8f0e1a2b3c4d5e6f',cH: 'abc'};}());</script></body></html>
    """.trimIndent()

    @Test fun theKindOfCloudflareCheckIsRead() {
        assertEquals(CheckKind.Waiting, browserCheckKind(interstitial("non-interactive")))
        assertEquals(CheckKind.Managed, browserCheckKind(interstitial("managed")))
        assertEquals(CheckKind.Interactive, browserCheckKind(interstitial("interactive")))
    }

    /** A check that names no kind, or isn't Cloudflare's, is still a check. */
    @Test fun aCheckOfNoKnownKindIsOther() {
        assertEquals(CheckKind.Other, browserCheckKind(interstitial("something-new")))
        assertEquals(CheckKind.Other, browserCheckKind("<html><head><title>DDoS-Guard</title></head><body>ddos-guard</body></html>"))
        assertEquals(CheckKind.Other, browserCheckKind("<html><head><title>Just a moment...</title></head><body></body></html>"))
    }

    @Test fun aPageThatIsNoCheckHasNoKind() {
        assertNull(browserCheckKind("<html><head><title>Chapter 1</title></head><body><p>Text.</p></body></html>"))
        // Prose that quotes the option's spelling is still prose.
        assertNull(browserCheckKind("<html><head><title>Chapter 3</title></head><body><p>cType: 'interactive'</p></body></html>"))
    }
}
