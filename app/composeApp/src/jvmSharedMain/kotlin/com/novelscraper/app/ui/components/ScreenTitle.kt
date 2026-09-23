package com.novelscraper.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared top-level screen title. One consistent position across every tab (clears
 * the status bar, no coloured app-bar separation — the whole screen is one
 * surface). Optional trailing [action] (e.g. a refresh / clear button).
 */
@Composable
fun ScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp)
            // A fixed height, so what the actions are doing never moves the
            // screen under them: a spinner taking a button's place used to
            // shove the whole page up and down.
            .height(TITLE_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) { action?.invoke() }
    }
}

/** The height every screen's title row keeps, whatever is in it. */
private val TITLE_HEIGHT = 56.dp
