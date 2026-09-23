package com.novelscraper.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/** One line of a context menu. */
data class MenuAction(val label: String, val onSelect: () -> Unit)

/**
 * The menu a thing offers when asked: right-click with a pointer, hold with a
 * finger.
 *
 * Every desktop has this and the app had none of it, so everything a novel could
 * do meant opening the novel first. The same menu serves both, because the
 * actions are the same; only the way of asking differs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithContextMenu(
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // Where the press landed, so the menu can open there. A menu that appears at
    // the corner of whatever was clicked, rather than under the pointer, means
    // crossing the screen to reach what you just asked for.
    var pressedAt by remember { mutableStateOf(Offset.Zero) }
    androidx.compose.foundation.layout.Box(
        modifier
            .combinedClickable(onClick = onClick, onLongClick = { open = true })
            .pointerInput(actions) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        // Every press, whichever button and whichever finger: a
                        // long press opens the same menu and wants the same place.
                        event.changes.firstOrNull()?.let { pressedAt = it.position }
                        if (event.buttons.isSecondaryPressed) {
                            open = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        content()
        // The menu hangs off a point of no size sitting where the press landed,
        // rather than off the whole tile: a dropdown measures from the bottom of
        // whatever it is attached to, so attaching it to the tile put the menu
        // below the cover, and it then flipped above to fit.
        androidx.compose.foundation.layout.Box(
            Modifier.offset {
                IntOffset(pressedAt.x.roundToInt(), pressedAt.y.roundToInt())
            },
        ) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (action in actions) {
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            open = false
                            action.onSelect()
                        },
                    )
                }
            }
        }
    }
}
