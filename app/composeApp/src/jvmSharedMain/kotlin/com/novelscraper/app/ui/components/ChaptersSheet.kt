package com.novelscraper.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelscraper.app.library.LibChapter

/**
 * Bottom-sheet table of contents: volumes as a collapsible accordion so a book
 * with thousands of chapters is navigable. Tap a chapter to jump; tap the trailing
 * circle to toggle its read state. Mirrors the web `ChaptersSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(
    title: String,
    chapters: List<LibChapter>,
    readPositions: Set<Int>,
    currentPos: Int,
    onJump: (Int) -> Unit,
    onToggleRead: (position: Int, read: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val volumes = remember(chapters) { groupVolumes(chapters) }
    var newestFirst by remember { mutableStateOf(false) }
    // Expand the volume that contains the current chapter (fall back to the first).
    var expanded by remember {
        mutableStateOf(chapters.firstOrNull { it.position == currentPos }?.volume
            ?: volumes.firstOrNull()?.number)
    }
    val ordered = remember(volumes, newestFirst) {
        if (newestFirst) volumes.sortedByDescending { it.number } else volumes
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${chapters.size} chapters", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .clickable { newestFirst = !newestFirst }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (newestFirst) "Newest" else "Oldest",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            ordered.forEach { vol ->
                val isOpen = expanded == vol.number
                item(key = "vol-${vol.number}") {
                    VolumeHeaderRow(
                        number = vol.number, chapterCount = vol.chapters.size, label = vol.label,
                        expanded = isOpen,
                        onClick = { expanded = if (isOpen) null else vol.number },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
                if (isOpen) {
                    val chs = if (newestFirst) vol.chapters.asReversed() else vol.chapters
                    items(chs, key = { "ch-${it.position}" }) { ch ->
                        ChapterRow(
                            ch = ch,
                            read = ch.position in readPositions,
                            current = ch.position == currentPos,
                            selectionMode = false, selected = false,
                            onClick = { onJump(ch.position) },
                            onLongClick = {},
                            onToggleRead = { onToggleRead(ch.position, ch.position !in readPositions) },
                        )
                    }
                }
            }
        }
    }
}
