package com.novelscraper.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The web app's star colour (Tailwind amber-400), so a rating looks the same on both. */
private val StarAmber = Color(0xFFFBBF24)

/**
 * Five stars. Pass [onRate] to make it tappable; leave it null for a read-only
 * display. Tapping the star that's already the rating clears it (reports 0),
 * the same as on the web.
 */
@Composable
fun StarRating(
    rating: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    onRate: ((Int) -> Unit)? = null,
) {
    val value = rating ?: 0
    Row(modifier) {
        for (n in 1..5) {
            val filled = n <= value
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (onRate != null) "Rate $n of 5" else null,
                tint = if (filled) StarAmber else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(size)
                    .then(
                        if (onRate != null) {
                            Modifier.clickable { onRate(if (value == n) 0 else n) }.padding(2.dp)
                        } else Modifier,
                    ),
            )
        }
    }
}
