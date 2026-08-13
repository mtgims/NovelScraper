package com.novelscraper.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelscraper.app.tts.KokoroVoices
import com.novelscraper.app.ui.theme.Kicker

/**
 * Kokoro speaker picker: a dropdown listing every voice by name, grouped under
 * nationality headers (English first). Replaces the raw "#n" stepper.
 */
@Composable
fun KokoroVoicePicker(currentId: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Voice", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                KokoroVoices.describe(currentId),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 420.dp),
        ) {
            KokoroVoices.groups.forEach { (section, voices) ->
                Text(
                    "${section.uppercase()} · ${voices.size}",
                    style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                voices.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(v.label) },
                        onClick = { onPick(v.id); open = false },
                        trailingIcon = if (v.id == currentId) {
                            { Icon(Icons.Filled.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                    )
                }
            }
        }
    }
}
