package com.novelscraper.app.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropertiesStoreTest {
    private val dir = Files.createTempDirectory("ns-store").toFile()

    @Test fun valuesSurviveAReload() {
        val f = File(dir, "ns.properties")
        val a = PropertiesStore(f)
        a.putString("base_url", "http://127.0.0.1:8803/")
        a.putFloat("font_scale", 1.2f)
        a.putInt("kokoro_speaker", 7)
        a.putBoolean("tts_auto_next", false)
        val cookies = setOf("host|ns_session=abc; path=/; httponly", "other|x=\"y\"=, é ü | 😀")
        a.putStringSet("cookies", cookies)
        PropertiesStore.flush()

        val b = PropertiesStore(f)
        assertEquals("http://127.0.0.1:8803/", b.getString("base_url", null))
        assertEquals(1.2f, b.getFloat("font_scale", 0f))
        assertEquals(7, b.getInt("kokoro_speaker", 0))
        assertEquals(false, b.getBoolean("tts_auto_next", true))
        assertEquals(cookies, b.getStringSet("cookies"))
        assertEquals("fallback", b.getString("missing", "fallback"))
        assertEquals(3, b.getInt("font_scale", 3), "wrong type falls back to the default")

        b.remove("cookies")
        PropertiesStore.flush()
        assertNull(PropertiesStore(f).getStringSet("cookies"))
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(f.toPath()))
    }

    @Test fun concurrentWritesAllLand() {
        val f = File(dir, "reader.properties")
        val s = PropertiesStore(f)
        (0 until 16).map { t -> thread { repeat(50) { i -> s.putInt("k_${t}_$i", i) } } }.forEach { it.join() }
        PropertiesStore.flush()
        val r = PropertiesStore(f)
        for (t in 0 until 16) for (i in 0 until 50) assertEquals(i, r.getInt("k_${t}_$i", -1))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
