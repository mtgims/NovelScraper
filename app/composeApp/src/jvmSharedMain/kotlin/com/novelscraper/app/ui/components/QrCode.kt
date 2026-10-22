package com.novelscraper.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novelscraper.app.sync.SyncQr

/**
 * [text] as a QR code. Always dark on white, whatever the theme: scanners
 * expect it, and some refuse a light-on-dark code.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val m = remember(text) { SyncQr.modules(text) }
    Canvas(modifier.size(size).semantics { contentDescription = "QR code of the sync code" }) {
        drawRect(Color.White)
        val cells = m.width + 2 * SyncQr.QUIET_ZONE
        val cell = this.size.minDimension / cells
        // Cells overlap by a hair, so no seam of white shows between them.
        val box = Size(cell + 0.5f, cell + 0.5f)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) drawRect(
                Color.Black,
                topLeft = Offset((x + SyncQr.QUIET_ZONE) * cell, (y + SyncQr.QUIET_ZONE) * cell),
                size = box,
            )
        }
    }
}
