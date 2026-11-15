package com.novelscraper.app.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelscraper.app.tts.KokoroVoices
import com.novelscraper.app.ui.theme.Kicker

/**
 * A voice dropdown: a field showing the voice, whose list opens right under it
 * (or just above, when there's no room below), as wide as the field, and
 * scrolls within. A menu hung off a plain row opened from the row's corner and,
 * long as the voice lists are, jumped to wherever it fitted, away from the tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceDropdown(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    items: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 420.dp),
        ) {
            items { open = false }
        }
    }
}

/** A voice in a [VoiceDropdown], ticked when it is the current one. */
@Composable
internal fun VoiceItem(text: String, current: Boolean, onClick: () -> Unit) =
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        trailingIcon = if (current) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else null,
    )

/**
 * Kokoro speaker picker: a dropdown listing every voice by name, grouped under
 * nationality headers (English first).
 */
@Composable
fun KokoroVoicePicker(
    currentId: Int,
    label: String = "Voice",
    /** When set, an "Automatic" entry (id -1) is offered, shown as this. */
    automatic: String? = null,
    onPick: (Int) -> Unit,
) {
    VoiceDropdown(
        label = label,
        value = if (automatic != null && currentId < 0) automatic else KokoroVoices.describe(currentId),
    ) { close ->
        if (automatic != null) VoiceItem("Automatic", currentId < 0) { onPick(-1); close() }
        KokoroVoices.groups.forEach { (section, voices) ->
            Text(
                "${section.uppercase()} · ${voices.size}",
                style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            voices.forEach { v -> VoiceItem(v.label, v.id == currentId) { onPick(v.id); close() } }
        }
    }
}

/** Supertonic's ten voices, the same dropdown as Kokoro's. */
@Composable
fun SupertonicVoicePicker(
    currentId: Int,
    label: String = "Voice",
    /** When set, an "Automatic" entry (id -1) is offered, shown as this. */
    automatic: String? = null,
    onPick: (Int) -> Unit,
) {
    val voices = com.novelscraper.app.tts.TtsModels.SUPERTONIC_VOICES
    VoiceDropdown(
        label = label,
        value = if (automatic != null && currentId < 0) automatic else voices.getOrElse(currentId) { voices.first() },
    ) { close ->
        if (automatic != null) VoiceItem("Automatic", currentId < 0) { onPick(-1); close() }
        voices.forEachIndexed { id, name -> VoiceItem(name, id == currentId) { onPick(id); close() } }
    }
}
