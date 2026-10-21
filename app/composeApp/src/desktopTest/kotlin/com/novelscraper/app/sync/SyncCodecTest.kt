package com.novelscraper.app.sync

import com.novelscraper.app.data.SyncChange
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Batch files come from other devices and shared folders: decoding has limits. */
class SyncCodecTest {
    private val codec = SyncCodec(null)              // a plain (unencrypted) folder
    private val name = BatchName("dev", 1, false)

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test fun aBatchThatUnpacksToTooMuchIsRefused() {
        val header = GZIPInputStream(codec.encode("dev", 1, "n", emptyList()).inputStream()).readBytes()
        // A valid batch, then 70 MB of padding that packs into a few kilobytes.
        val bomb = gzip(header + ByteArray(70 * 1024 * 1024) { ' '.code.toByte() })
        assertTrue(bomb.size < 200 * 1024, "the bomb is small: ${bomb.size} bytes")
        val e = assertFailsWith<BadBatchException> { codec.decode(name, bomb) }
        assertTrue("MiB" in e.message.orEmpty(), "refused for its size: ${e.message}")
    }

    @Test fun anOrdinaryBatchStillDecodes() {
        val change = SyncChange("novel", "k", "{}", 5, "dev")
        val batch = codec.decode(name, codec.encode("dev", 1, "n", listOf(change)))
        assertEquals(listOf(change), batch.changes)
    }
}
