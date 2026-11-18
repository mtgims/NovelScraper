package com.novelscraper.app.tts

import com.novelscraper.app.platform.Os
import com.novelscraper.app.platform.appVersion
import com.novelscraper.app.platform.settingsStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpuVoiceTest {

    /**
     * The AppImage's launcher leaves `_JPACKAGE_LAUNCHER` in the app's
     * environment, and a copy of it started with that variable took
     * `--gpu-probe` for a Java option and quit, so the card was never tried.
     */
    @Test
    fun theProbeCopyDoesNotInheritTheLaunchersSecondStage() {
        if (Os.isWindows) return  // needs sh; the variable is the Linux launcher's
        val log = Files.createTempFile("probe", ".log").toFile()
        try {
            val inherited = mapOf("PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"), "_JPACKAGE_LAUNCHER" to "1")
            val p = GpuVoice.probeProcess(
                listOf("sh", "-c", "echo stage=\${_JPACKAGE_LAUNCHER:-none} adapter=\$SHERPA_ONNX_DML_DEVICE"),
                adapter = 2, log = log, inherited = inherited,
            ).start()
            assertTrue(p.waitFor(10, TimeUnit.SECONDS))
            assertEquals("stage=none adapter=2", log.readText().trim())
        } finally {
            log.delete()
        }
    }

    /** A failure recorded before 1.3.3 (no version beside it) is tried again;
     *  one this version found stands until the switch is turned off and on. */
    @Test
    fun failuresFromAnotherVersionAreTriedAgain() {
        val prefs = settingsStore("tts-gpu")
        val key = "cuda12-sherpa1.13.8-ort1.28.2-cudnn9.10.2/kokoro-multi-lang-v1_0"
        prefs.remove("probes-failed-in")
        prefs.putStringSet("probes-failed", setOf(key))
        GpuVoice.forgetOldFailures()
        assertNull(prefs.getStringSet("probes-failed"))

        prefs.putStringSet("probes-failed", setOf(key))
        prefs.putString("probes-failed-in", appVersion)
        GpuVoice.forgetOldFailures()
        assertEquals(setOf(key), prefs.getStringSet("probes-failed"))

        prefs.putString("probes-failed-in", "0.0.0-older")
        GpuVoice.forgetOldFailures()
        assertNull(prefs.getStringSet("probes-failed"))
    }

    /** With no card detected this run, nothing is "from an earlier version":
     *  the pack in use was offered for removal along with everything else. */
    @Test
    fun noPackIsStaleWhenNoCardWasFound() {
        val root = Files.createTempDirectory("gpu").toFile()
        try {
            val cuda = File(root, GpuVoice.Backend.CUDA.packId).apply { mkdirs() }
            val old = File(root, "cuda12-sherpa1.12.0-older").apply { mkdirs() }
            assertEquals(emptyList(), GpuVoice.stalePacks(root, null))
            assertEquals(listOf(old), GpuVoice.stalePacks(root, GpuVoice.Backend.CUDA))
            assertEquals(setOf(cuda, old), GpuVoice.stalePacks(root, GpuVoice.Backend.DIRECTML).toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    /** Narration leaves the card below [GpuVoice.LEAVE_BELOW] free and goes
     *  back only with [GpuVoice.LOAD_ABOVE], so it doesn't bounce. Without an
     *  answer from the driver nothing moves. */
    @Test
    fun narrationLeavesTheCardOnlyWhenItsMemoryRunsShort() {
        val was = GpuVoice.freeMemory
        try {
            GpuVoice.onCard = true
            GpuVoice.freeMemory = { 4L shl 30 }
            assertNull(GpuVoice.leaveCard())
            GpuVoice.freeMemory = { null }
            assertNull(GpuVoice.leaveCard())

            GpuVoice.freeMemory = { 900L shl 20 }
            assertNotNull(GpuVoice.leaveCard())
            assertTrue(GpuVoice.overloaded)
            assertTrue(GpuVoice.problem!!.contains("900 MB"), GpuVoice.problem)

            GpuVoice.onCard = false
            assertNull(GpuVoice.leaveCard())   // already on the processor
            GpuVoice.freeMemory = { 2L shl 30 }
            assertFalse(GpuVoice.backToCard())
            assertTrue(GpuVoice.overloaded)
            GpuVoice.freeMemory = { 5L shl 30 }
            assertTrue(GpuVoice.backToCard())
            assertFalse(GpuVoice.overloaded)
            assertNull(GpuVoice.problem)
            assertFalse(GpuVoice.backToCard())   // nothing to go back from
        } finally {
            GpuVoice.freeMemory = was
            GpuVoice.onCard = false
        }
    }
}
