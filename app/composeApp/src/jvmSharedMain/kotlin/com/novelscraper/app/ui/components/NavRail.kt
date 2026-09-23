package com.novelscraper.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The navigation down the side of the window, for a computer.
 *
 * A bar of small targets floating at the bottom is a phone pattern: a thumb
 * finds it without looking, a pointer has to be aimed at it. On a window this
 * size the navigation belongs along the leading edge, where it is always in the
 * same place, each destination is a full-width row big enough to hit without
 * care, and the one under the pointer says so before it is clicked.
 *
 * Wide windows get the labels; narrower ones keep the icons alone.
 */
@Composable
fun NavRail(
    current: String?,
    labelled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxHeight().width(if (labelled) RAIL_WIDE else RAIL_NARROW),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (labelled) {
                Text(
                    "NovelScraper",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 14.dp),
                )
            } else {
                Spacer(Modifier.height(12.dp))
            }
            for (item in navItems) {
                RailItem(
                    label = item.label,
                    selected = current == item.route,
                    labelled = labelled,
                    icon = { tint -> Icon(item.icon, contentDescription = item.label, tint = tint) },
                    onClick = { onSelect(item.route) },
                )
            }
        }
    }
}

@Composable
private fun RailItem(
    label: String,
    selected: Boolean,
    labelled: Boolean,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val foreground = when {
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A whole row, not an icon: a pointer should not have to be accurate.
            .heightIn(min = ROW_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (labelled) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (labelled) Arrangement.Start else Arrangement.Center,
    ) {
        icon(foreground)
        if (labelled) {
            Text(
                label,
                color = foreground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** The rail's own footprint, so screens know how much window is left. */
val RAIL_WIDE = 188.dp
val RAIL_NARROW = 68.dp
private val ROW_HEIGHT = 44.dp

/** Padding for content laid out beside the rail. */
val railContentPadding = PaddingValues(0.dp)
