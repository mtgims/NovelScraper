package com.novelscraper.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelscraper.app.library.LibChapter
import com.novelscraper.app.ui.theme.Kicker

/** A volume with its chapters, in reading order. */
data class TocVolume(val number: Int, val chapters: List<LibChapter>)

/** Group a flat chapter list into volumes, preserving order. */
fun groupVolumes(chapters: List<LibChapter>): List<TocVolume> {
    val out = ArrayList<TocVolume>()
    var num = Int.MIN_VALUE
    var cur = ArrayList<LibChapter>()
    for (ch in chapters) {
        if (ch.volume != num) {
            if (cur.isNotEmpty()) out.add(TocVolume(num, cur))
            num = ch.volume
            cur = ArrayList()
        }
        cur.add(ch)
    }
    if (cur.isNotEmpty()) out.add(TocVolume(num, cur))
    return out
}

/** Collapsible "VOLUME n · N ch" header row. */
@Composable
fun VolumeHeaderRow(
    number: Int,
    chapterCount: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "VOLUME $number",
            style = Kicker.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$chapterCount ch",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.Filled.ExpandMore, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).rotate(if (expanded) 180f else 0f),
        )
    }
}

/**
 * One chapter row. Tap opens it (or toggles selection in [selectionMode]); the
 * trailing control toggles read state, or shows a checkbox in selection mode.
 * Long-press starts selection via [onLongClick].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChapterRow(
    ch: LibChapter,
    read: Boolean,
    current: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    else if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    else MaterialTheme.colorScheme.surface
    Row(
        modifier.fillMaxWidth().background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "${ch.position}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(width = 34.dp, height = 20.dp).padding(end = 6.dp),
        )
        Text(
            ch.title.ifBlank { "Chapter ${ch.number}" },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (read && !current) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (ch.downloaded) {
            Icon(Icons.Filled.DownloadDone, contentDescription = "Downloaded",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
        if (current) {
            Text("here", style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 4.dp))
        }
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            IconButton(onClick = onToggleRead) {
                if (read) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Mark as unread",
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                } else {
                    Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = "Mark as read",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
