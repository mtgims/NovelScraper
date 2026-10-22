package com.novelscraper.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncQrTest {

    /** [SyncQr.modules] drawn as a camera would see it: [scale] pixels a cell,
     *  the quiet zone around it, in rows [stride] bytes long (padded, as a
     *  camera's planes often are). */
    private fun frame(text: String, scale: Int = 6, stride: Int? = null): Triple<ByteArray, Int, Int> {
        val m = SyncQr.modules(text)
        val side = (m.width + 2 * SyncQr.QUIET_ZONE) * scale
        val rowStride = stride ?: side
        val y = ByteArray(rowStride * side) { 0xF0.toByte() }   // light paper
        for (py in 0 until side) for (px in 0 until side) {
            val cx = px / scale - SyncQr.QUIET_ZONE
            val cy = py / scale - SyncQr.QUIET_ZONE
            if (cx in 0 until m.width && cy in 0 until m.height && m[cx, cy]) y[py * rowStride + px] = 0x10
        }
        return Triple(y, rowStride, side)
    }

    @Test fun aShownCodeScansBackToTheSameCode() {
        val code = SyncCode.generate()
        val (y, stride, side) = frame(code.toString())
        val read = SyncQr.decode(y, stride, side, side)
        assertEquals(code.toString(), read)
        assertEquals(code.toString(), SyncCode.parse(read!!).toString())
    }

    @Test fun paddedCameraRowsAreRead() {
        val code = SyncCode.generate().toString()
        val unpadded = (SyncQr.modules(code).width + 2 * SyncQr.QUIET_ZONE) * 6
        val (y, stride, side) = frame(code, stride = unpadded + 64)
        assertEquals(code, SyncQr.decode(y, stride, side, side))
    }

    @Test fun theCodeStaysASmallSymbol() {
        // 35 characters of upper case, digits and dashes: QR's alphanumeric
        // mode, which keeps the symbol at version 2 (25 cells), easy to scan.
        val m = SyncQr.modules(SyncCode.generate().toString())
        assertEquals(25, m.width)
    }

    @Test fun aFrameWithoutACodeIsNothing() {
        val side = 200
        assertNull(SyncQr.decode(ByteArray(side * side) { (it % 251).toByte() }, side, side, side))
    }
}
