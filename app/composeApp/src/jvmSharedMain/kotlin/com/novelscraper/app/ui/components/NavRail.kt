package com.novelscraper.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
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
    onToggleWidth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The rail widens and narrows rather than jumping between two widths, and the
    // labels come and go with it. The icons stay where they are throughout: they
    // sit in a slot of their own at the start of each row, which is what makes
    // the movement read as the panel opening rather than everything rearranging.
    val width by animateDpAsState(
        targetValue = if (labelled) RAIL_WIDE else RAIL_NARROW,
        animationSpec = tween(RAIL_ANIMATION_MS, easing = FastOutSlowInEasing),
        label = "rail width",
    )
    Surface(
        modifier = modifier.fillMaxHeight().width(width).clipToBounds(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 8.dp).width(RAIL_WIDE - 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // The app already knows its own name; the space is better spent on
            // the one control the rail itself needs.
            RailItem(
                label = "",
                selected = false,
                // No word beside it: three lines at the top of a sidebar have
                // meant this for thirty years, and the label only took up the
                // room the reader was trying to reclaim.
                labelled = false,
                icon = { tint -> Icon(Icons.Filled.Menu, contentDescription = "Narrow or widen the sidebar", tint = tint) },
                onClick = onToggleWidth,
            )
            Spacer(Modifier.height(8.dp))
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
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A fixed slot, so the icon holds its place while the panel moves.
        Box(Modifier.width(ICON_SLOT).height(ROW_HEIGHT), contentAlignment = Alignment.Center) {
            icon(foreground)
        }
        AnimatedVisibility(
            visible = labelled,
            // Anchored at the start, so a label is taken away from its far end
            // like a panel closing over it. Left to itself it shrinks the other
            // way, which eats the first letters and leaves "rary" of "Library".
            enter = fadeIn(tween(RAIL_ANIMATION_MS)) +
                expandHorizontally(
                    tween(RAIL_ANIMATION_MS, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Start,
                ),
            exit = fadeOut(tween(RAIL_ANIMATION_MS / 2)) +
                shrinkHorizontally(
                    tween(RAIL_ANIMATION_MS, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Start,
                ),
        ) {
            Text(
                label,
                color = foreground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

/** The rail's own footprint, so screens know how much window is left. */
val RAIL_WIDE = 188.dp
val RAIL_NARROW = 68.dp
private val ROW_HEIGHT = 44.dp

/** The slot an icon keeps at the start of a row, whatever the rail is doing. */
private val ICON_SLOT = 52.dp

/** Long enough to be seen as movement, short enough not to be waited on. */
private const val RAIL_ANIMATION_MS = 220

/** Padding for content laid out beside the rail. */
val railContentPadding = PaddingValues(0.dp)
