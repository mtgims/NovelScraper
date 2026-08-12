package com.novelscraper.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.tts.TtsController
import java.util.Locale

private fun fmt(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return (if (h > 0) "$h:" else "") + "$m:" + ss.toString().padStart(2, '0')
}

/** The reader's floating "Listen" pill — mirrors the web TTS player. */
@Composable
fun ReaderTtsBar(bookId: Int, position: Int, bookTitle: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s by TtsController.state.collectAsState()
    val activeHere = s.active && s.bookId == bookId && s.position == position
    var expanded by remember { mutableStateOf(false) }

    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { TtsController.play(ctx, bookId, position, bookTitle) }

    fun startListen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        else TtsController.play(ctx, bookId, position, bookTitle)
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        if (!activeHere) {
            ListenPill(onClick = ::startListen)
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
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
                    SeekSlider(count = count, index = s.sentenceIndex) { TtsController.seek(ctx, it) }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { TtsController.rewind(ctx) }) {
                            Icon(Icons.Filled.FastRewind, contentDescription = "Rewind")
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp).clickable { TtsController.toggle(ctx) },
                        ) {
                            Icon(
                                if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (s.playing) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        IconButton(onClick = { TtsController.forward(ctx) }) {
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
                        IconButton(onClick = { TtsController.stop(ctx) }) {
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
    val ctx = LocalContext.current
    val rate by ReaderPrefs.ttsRate.collectAsState()
    val voiceName by ReaderPrefs.ttsVoice.collectAsState()

    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text("Speed · ${"%.1f".format(rate)}×", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = rate,
            onValueChange = { ReaderPrefs.setTtsRate(it) },
            onValueChangeFinished = { TtsController.applySettings(ctx) },
            valueRange = 0.5f..2.5f,
            modifier = Modifier.fillMaxWidth(),
        )
        VoicePicker(current = voiceName) { name ->
            ReaderPrefs.setTtsVoice(name)
            TtsController.applySettings(ctx)
        }
    }
}

private data class VoiceOpt(val name: String, val label: String)

@Composable
private fun VoicePicker(current: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var voices by remember { mutableStateOf<List<VoiceOpt>>(emptyList()) }
    var open by remember { mutableStateOf(false) }

    // Enumerate the device's voices for the current language via a short-lived engine.
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val cur = Locale.getDefault().language
                voices = runCatching {
                    engine!!.voices
                        // Every installed voice, all languages; skip only ones the
                        // engine reports as not-installed. Current language first.
                        ?.filter { it.name != null && it.features?.contains("notInstalled") != true }
                        ?.sortedWith(
                            compareBy(
                                { it.locale.language != cur },
                                { it.locale.displayName },
                                { it.name },
                            ),
                        )
                        ?.map { VoiceOpt(it.name, "${it.locale.displayName} · ${it.name.substringAfterLast('-')}") }
                        ?: emptyList()
                }.getOrDefault(emptyList())
            }
        }
        onDispose { engine?.shutdown() }
    }

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
