package com.novelscraper.app.extensions

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Where plugin code and repository indexes may be fetched from.
 *
 * The app permits cleartext generally, because sources read novel sites the
 * reader picked and many are still http. This is a different thing: an index
 * decides what is offered and plugin code runs on the device with that source's
 * tokens and cookies in reach, so over http whoever sits on the path decides
 * what runs.
 */
class PluginOriginTest {

    @Test fun httpsIsFine() {
        Extensions.requireTrustworthyOrigin("https://example.test/index.json")
        Extensions.requireTrustworthyOrigin("https://example.test/plugins/acme.js")
    }

    @Test fun plainHttpIsRefused() {
        val e = assertFailsWith<IllegalStateException> {
            Extensions.requireTrustworthyOrigin("http://example.test/index.json")
        }
        assertTrue("https" in e.message.orEmpty(), "the refusal says what is wrong: ${e.message}")
    }

    @Test fun loopbackOverHttpIsAllowedForWritingSources() {
        Extensions.requireTrustworthyOrigin("http://localhost:8080/index.json")
        Extensions.requireTrustworthyOrigin("http://127.0.0.1:8080/index.json")
    }

    @Test fun somethingThatIsNotAnAddressIsRefused() {
        assertFailsWith<IllegalStateException> { Extensions.requireTrustworthyOrigin("not a url") }
        assertFailsWith<IllegalStateException> { Extensions.requireTrustworthyOrigin("ftp://example.test/i.json") }
    }
}
