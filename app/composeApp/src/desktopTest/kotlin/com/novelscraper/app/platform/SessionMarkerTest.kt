package com.novelscraper.app.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionMarkerTest {
    private val dir: File = Files.createTempDirectory("session").toFile()
    private val log = File(dir, "novelscraper.log")
    private fun marker() = SessionMarker(File(dir, "session"), log)

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    @Test
    fun aSessionThatClosedProperlyLeavesNothingToReport() {
        val first = marker()
        assertNull(first.begin("1.3.5"))
        first.end()
        assertNull(marker().begin("1.3.5"))
    }

    /** 2026-11-17: the app aborted mid-narration and nothing said so; the
     *  next start knew neither that it had crashed nor what it was doing. */
    @Test
    fun aSessionThatDiedIsReportedWithWhatItWasDoingAndTheEndOfItsLog() {
        log.writeText((1..300).joinToString("\n") { "22:14:%02d I/KokoroEngine: gen $it".format(it % 60) } + "\n")
        val crashed = marker()
        assertNull(crashed.begin("1.3.4"))
        crashed.doing("narrating “Infinite Mana” on the graphics card")
        // ...and the process ends without end(): no shutdown hook ran.

        val next = marker()
        val report = assertNotNull(next.begin("1.3.5"))
        assertEquals("narrating “Infinite Mana” on the graphics card", report.doing)
        assertEquals("1.3.4", report.version)
        assertEquals(SessionMarker.LOG_LINES, report.logTail.size)
        assertTrue(report.logTail.last().endsWith("gen 300"))
        assertTrue(report.details().contains("narrating “Infinite Mana”"))

        // Reported once: this session is marked as running in its place.
        next.end()
        assertNull(marker().begin("1.3.5"))
    }

    @Test
    fun whatItIsDoingIsClearedWhenItStops() {
        val m = marker()
        m.begin("1.3.5")
        m.doing("narrating “A” on the processor")
        m.doing(null)
        assertNull(marker().begin("1.3.5")?.doing)
    }
}
