package com.novelscraper.app.net

import com.novelscraper.app.platform.PropertiesStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class ForgetServerTest {

    /** A 1.1 install signed in to the server kept these; none may stay. */
    @Test fun theOldSessionIsWipedOnStart() {
        val f = File(Files.createTempDirectory("ns-old").toFile(), "ns.properties")
        PropertiesStore(f).apply {
            putString("base_url", "https://novelscraper.com/")
            putString("account_user", "someone")
            putStringSet("cookies", setOf("novelscraper.com|ns_session=abc; path=/; httponly"))
        }
        PropertiesStore.flush()

        Net.forgetServer { PropertiesStore(f) }
        PropertiesStore.flush()
        assertTrue(PropertiesStore(f).keys().isEmpty(), "left behind: ${PropertiesStore(f).keys()}")
    }
}
