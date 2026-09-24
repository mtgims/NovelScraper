package com.novelscraper.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.platform.rememberNarrationPermission
import com.novelscraper.app.platform.rememberSystemVoices
import com.novelscraper.app.tts.SleepTimer
import com.novelscraper.app.tts.TtsController
import com.novelscraper.app.tts.TtsModels

private fun fmt(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return (if (h > 0) "$h:" else "") + "$m:" + ss.toString().padStart(2, '0')
}

/** The reader's floating "Listen" pill — mirrors the web TTS player.
 *  [startIndex] returns the sentence to begin narration from (the one on screen). */
@Composable
fun ReaderTtsBar(
    bookId: Int,
    position: Int,
    bookTitle: String,
    startIndex: () -> Int = { 0 },
    modifier: Modifier = Modifier,
) {
    val s by TtsController.state.collectAsState()
    val activeHere = s.active && s.bookId == bookId && s.position == position
    var expanded by remember { mutableStateOf(false) }

    val startListen = rememberNarrationPermission {
        TtsController.play(bookId, position, bookTitle, startIndex())
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        if (!activeHere) {
            ListenPill(onClick = startListen)
        } else {
            Surface(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 12.dp,
                tonalElevation = 3.dp,
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    AnimatedVisibility(expanded) { SettingsPanel() }

                    // Seek across sentences (drag commits on release).
                    val count = s.sentenceCount.coerceAtLeast(1)
                    SeekSlider(count = count, index = s.sentenceIndex) { TtsController.seek(it) }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { TtsController.rewind() }) {
                            Icon(Icons.Filled.FastRewind, contentDescription = "Rewind")
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp).clickable { TtsController.toggle() },
                        ) {
                            Icon(
                                if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (s.playing) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        IconButton(onClick = { TtsController.forward() }) {
                            Icon(Icons.Filled.FastForward, contentDescription = "Forward")
                        }
                        Text(
                            "${fmt(s.elapsedSec)} / ~${fmt(s.totalSec)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                        Box(Modifier.weight(1f))
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Settings",
                                modifier = Modifier.rotate(if (expanded) 180f else 0f),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { TtsController.stop() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Stop",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListenPill(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 10.dp,
        tonalElevation = 3.dp,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Headphones, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text("Listen", style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SeekSlider(count: Int, index: Int, onSeek: (Int) -> Unit) {
    // Local drag state so the value follows the finger and commits on release.
    var dragging by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(index.toFloat()) }
    if (!dragging) pos = index.coerceIn(0, count - 1).toFloat()
    Slider(
        value = pos,
        onValueChange = { dragging = true; pos = it },
        onValueChangeFinished = { dragging = false; onSeek(pos.toInt()) },
        valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsPanel() {
    val rate by ReaderPrefs.ttsRate.collectAsState()
    val voiceName by ReaderPrefs.ttsVoice.collectAsState()
    val engine by ReaderPrefs.ttsEngine.collectAsState()
    val autoNext by ReaderPrefs.ttsAutoNext.collectAsState()
    val kokoroSpeaker by ReaderPrefs.kokoroSpeaker.collectAsState()
    val supertonicSpeaker by ReaderPrefs.supertonicSpeaker.collectAsState()
    val piperVoice by ReaderPrefs.piperVoice.collectAsState()

    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text("Speed · ${"%.1f".format(rate)}×", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = rate,
            onValueChange = { ReaderPrefs.setTtsRate(it) },
            onValueChangeFinished = { TtsController.applySettings() },
            valueRange = 0.5f..2.5f,
            modifier = Modifier.fillMaxWidth(),
        )

        when (engine) {
            ReaderPrefs.ENGINE_KOKORO ->
                KokoroVoicePicker(currentId = kokoroSpeaker) { id ->
                    ReaderPrefs.setKokoroSpeaker(id)
                    TtsController.applySettings()
                }
            ReaderPrefs.ENGINE_SUPERTONIC ->
                SupertonicVoicePicker(currentId = supertonicSpeaker) { id ->
                    ReaderPrefs.setSupertonicSpeaker(id)
                    TtsController.applySettings()
                }
            ReaderPrefs.ENGINE_PIPER ->
                PiperVoiceDropdown(current = piperVoice) { id ->
                    ReaderPrefs.setPiperVoice(id)
                    TtsController.applySettings()
                }
            else ->
                VoicePicker(current = voiceName) { name ->
                    ReaderPrefs.setTtsVoice(name)
                    TtsController.applySettings()
                }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Auto next chapter", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            Switch(checked = autoNext, onCheckedChange = { ReaderPrefs.setTtsAutoNext(it) })
        }

        SleepTimerRow()
    }
}

/** Stop narrating after a while: for listening in bed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerRow() {
    val mode by SleepTimer.mode.collectAsState()
    val left by SleepTimer.remainingSec.collectAsState()

    Text(
        when (val m = mode) {
            SleepTimer.Mode.Off -> "Sleep timer"
            SleepTimer.Mode.ChapterEnd -> "Sleep timer · stops at the end of this chapter"
            is SleepTimer.Mode.Minutes -> "Sleep timer · ${fmt(left)} left of ${m.total} min"
        },
        style = MaterialTheme.typography.labelMedium,
        color = if (mode == SleepTimer.Mode.Off) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(15, 30, 45, 60).forEach { minutes ->
            FilterChip(
                selected = (mode as? SleepTimer.Mode.Minutes)?.total == minutes,
                onClick = {
                    if ((mode as? SleepTimer.Mode.Minutes)?.total == minutes) SleepTimer.cancel()
                    else SleepTimer.setMinutes(minutes)
                },
                label = { Text("$minutes min") },
            )
        }
        FilterChip(
            selected = mode == SleepTimer.Mode.ChapterEnd,
            onClick = {
                if (mode == SleepTimer.Mode.ChapterEnd) SleepTimer.cancel() else SleepTimer.setChapterEnd()
            },
            label = { Text("End of chapter") },
        )
    }
}

@Composable
private fun VoicePicker(current: String, onPick: (String) -> Unit) {
    val voices = rememberSystemVoices()
    var open by remember { mutableStateOf(false) }

    val label = if (current.isBlank()) "Default voice"
    else voices.firstOrNull { it.name == current }?.label ?: current

    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Voice", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 10.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Default voice") }, onClick = { onPick(""); open = false })
            voices.forEach { v ->
                DropdownMenuItem(text = { Text(v.label) }, onClick = { onPick(v.name); open = false })
            }
        }
    }
}

/** Switch among already-downloaded Piper voices (new ones are added in Settings). */
@Composable
private fun PiperVoiceDropdown(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val installed = TtsModels.PIPER_VOICES.filter { TtsModels.isModelReady(it.id) }
    val cur = TtsModels.PIPER_VOICES.firstOrNull { it.id == current }

    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Voice", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(cur?.let { "${it.name} · ${it.accent}" } ?: "Amy",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 10.dp))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            installed.forEach { v ->
                DropdownMenuItem(
                    text = { Text("${v.name} · ${v.accent}") },
                    onClick = { onPick(v.id); open = false },
                    trailingIcon = if (v.id == current) {
                        { Icon(Icons.Filled.Check, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                )
            }
            DropdownMenuItem(
                text = {
                    Text("More voices in Settings…", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                onClick = { open = false }, enabled = false,
            )
        }
    }
}
