package com.novelscraper.app.platform

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
