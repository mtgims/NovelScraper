package com.novelscraper.app.sync

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The sync code as a QR code, for a phone to scan instead of typing thirty
 * characters. It holds the code's own text and nothing else, so a phone's
 * ordinary camera app shows the same code a person would read off the screen.
 */
object SyncQr {

    /** The QR code's modules (true is dark), one per cell, without a margin:
     *  whoever draws it adds the quiet zone. */
    fun modules(text: String): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(
            EncodeHintType.MARGIN to 0,
            // Medium: survives a glare or a smudge on the screen, and the code
            // still fits a small, easily scanned symbol.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        ),
    )

    /** Cells of light margin a scanner needs around the code. */
    const val QUIET_ZONE = 4

    /**
     * The text of a QR code in one camera frame's brightness plane (the Y of
     * YUV), or null if there is none. [rowStride] is the plane's bytes per row,
     * which may be more than [width].
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
        } catch (_: ReaderException) {
            null
        }
    }

    private val HINTS = mapOf(DecodeHintType.TRY_HARDER to true)
}
