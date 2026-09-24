package com.novelscraper.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState

/**
 * The window's own title bar, drawn by the app.
 *
 * Windows draws a bar of its own in its own colours, which sits above a dark
 * app looking like a strip of someone else's program. Given an undecorated
 * window the app draws the bar instead: the same surface as the sidebar, in
 * whichever theme is chosen, with the buttons where Windows puts them.
 *
 * It is what the window is dragged by, and a double-click on it maximises and
 * restores, as a title bar should.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WindowScope.TitleBar(state: WindowState, onClose: () -> Unit) {
    val maximized = state.placement == WindowPlacement.Maximized
    fun toggleMaximised() {
        state.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
    }

    WindowDraggableArea {
        Row(
            Modifier
                .fillMaxWidth()
                .height(TITLE_BAR_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .onClick(onDoubleClick = { toggleMaximised() }, onClick = {}),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "NovelScraper",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp).weight(1f),
            )
            WindowButton(Icons.Filled.Minimize, "Minimise") { state.isMinimized = true }
            WindowButton(
                if (maximized) Icons.Filled.FilterNone else Icons.Filled.CropSquare,
                if (maximized) "Restore" else "Maximise",
            ) { toggleMaximised() }
            WindowButton(Icons.Filled.Close, "Close", danger = true, onClick = onClose)
        }
    }
}

/** One of the three, sized and lit the way Windows lights its own. */
@Composable
private fun WindowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        hovered && danger -> MaterialTheme.colorScheme.error
        hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val tint = when {
        hovered && danger -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .width(46.dp)
            .height(TITLE_BAR_HEIGHT)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(15.dp))
    }
}

val TITLE_BAR_HEIGHT = 32.dp
