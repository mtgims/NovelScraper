package com.novelscraper.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelscraper.app.data.CollectionRead

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionTabs(
    collections: List<CollectionRead>,
    activeTab: Int?,
    onSelect: (Int?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Int, String) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf<EditState?>(null) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf<CollectionRead?>(null) }

    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip("All", selected = activeTab == null, onClick = { onSelect(null) }, onLongClick = {})
        collections.forEach { c ->
            Box {
                Chip(
                    label = c.name,
                    selected = activeTab == c.id,
                    onClick = { onSelect(c.id) },
                    onLongClick = { menuFor = c.id },
                )
                DropdownMenu(expanded = menuFor == c.id, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        menuFor = null; editing = EditState(c.id, c.name)
                    })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
                        menuFor = null; confirmDelete = c
                    })
                }
            }
        }
        IconButton(onClick = { editing = EditState(null, "") }) {
            Icon(Icons.Filled.Add, contentDescription = "New collection")
        }
    }

    editing?.let { st ->
        NameDialog(
            title = if (st.id == null) "New collection" else "Rename collection",
            initial = st.name,
            onConfirm = { name ->
                if (st.id == null) onCreate(name) else onRename(st.id, name)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${c.name}”?") },
            text = { Text("The collection is removed. Your novels are kept.") },
            confirmButton = {
                TextButton(onClick = { onDelete(c.id); confirmDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

private data class EditState(val id: Int?, val name: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                singleLine = true, label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
