package com.novelscraper.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Keeps a column of content to a readable width in a wide window.
 *
 * A list of settings or of jobs stretched across a whole monitor is a line of
 * text with a metre of nothing after it, and the eye loses which row it was on.
 * On a phone this changes nothing: the window is narrower than the limit.
 */
@Composable
fun ContentWidth(
    modifier: Modifier = Modifier,
    max: Dp = READABLE_WIDTH,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = max), content = content)
    }
}

/** About ninety characters of the app's body text. */
val READABLE_WIDTH = 860.dp
