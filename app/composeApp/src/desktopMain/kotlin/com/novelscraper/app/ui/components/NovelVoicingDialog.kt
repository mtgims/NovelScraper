package com.novelscraper.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.library.Library
import com.novelscraper.app.tts.ExpressiveReading
import com.novelscraper.app.tts.KokoroVoices
import com.novelscraper.app.tts.NovelVoicing
import com.novelscraper.app.tts.NovelVoicings
import com.novelscraper.app.tts.Quote
import com.novelscraper.app.tts.StutterMark
import com.novelscraper.app.tts.SystemMark
import com.novelscraper.app.tts.TtsController
import com.novelscraper.app.tts.TtsModels
import com.novelscraper.app.tts.VoicingDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many stored chapters "Detect" reads: plenty to tell, quick to count. */
private const val DETECT_CHAPTERS = 40

/**
 * A novel's own expressive reading: on or off, the dialogue voice, and what the
 * novel uses to mark dialogue and stutters. Anything left alone follows
 * Settings, Narration.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NovelVoicingDialog(bookId: Int, title: String, onDismiss: () -> Unit) {
    var v by remember(bookId) { mutableStateOf(NovelVoicings.get(bookId)) }
    var found by remember(bookId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val engine by ReaderPrefs.ttsEngine.collectAsState()
    val generalOn by ReaderPrefs.ttsExpressive.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Expressive reading") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(title, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                Heading("For this novel")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("As in Settings (${if (generalOn) "on" else "off"})", v.enabled == null) {
                        v = v.copy(enabled = null)
                    }
                    Choice("On", v.enabled == true) { v = v.copy(enabled = true) }
                    Choice("Off", v.enabled == false) { v = v.copy(enabled = false) }
                }

                DialogueVoice(engine, v) { v = it }

                Heading("Dialogue is marked with")
                val quotes = v.quotes ?: Quote.AUTOMATIC
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Quote.entries.forEach { q ->
                        Choice(q.label, q in quotes) {
                            val next = if (q in quotes) quotes - q else quotes + q
                            v = v.copy(quotes = next.takeIf { it != Quote.AUTOMATIC })
                        }
                    }
                }
                when {
                    v.quotes == null -> "Automatic: double quotes, corner brackets and guillemets."
                    quotes.isEmpty() -> "Nothing: every line is read in the narrator's voice."
                    Quote.SINGLE_STRAIGHT in quotes || Quote.SINGLE_CURLY in quotes ->
                        "Single quotes are also apostrophes, so only pick them for a novel that speaks in them."
                    else -> null
                }?.let { Note(it) }

                Heading("Stutters are written like")
                val marks = v.stutters ?: StutterMark.entries.toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StutterMark.entries.forEach { m ->
                        Choice(m.label, m in marks) {
                            val next = if (m in marks) marks - m else marks + m
                            v = v.copy(stutters = next.takeIf { it != StutterMark.entries.toSet() })
                        }
                    }
                }
                Note(if (marks.isEmpty()) "None: hyphens and dashes are read as written." else
                    "Only a word that the letters begin counts, so \"well-known\" stays as it is.")

                Heading("Whispers and shouts")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("On", v.dynamics != false) { v = v.copy(dynamics = null) }
                    Choice("Off", v.dynamics == false) { v = v.copy(dynamics = false) }
                }
                Note("A line the narration says was whispered is read quieter and a little slower; one " +
                    "shouted, or written in capitals, louder and a little faster.")

                Heading("System messages are marked with")
                val system = v.system.orEmpty()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SystemMark.entries.forEach { m ->
                        Choice(m.label, m in system) {
                            val next = if (m in system) system - m else system + m
                            v = v.copy(system = next.ifEmpty { null })
                        }
                    }
                }
                Note(if (system.isEmpty()) "None. Pick one for a LitRPG novel whose messages are in brackets; " +
                    "it's off unless picked, since brackets also hold author notes."
                    else "Read in a voice of their own.")
                if (system.isNotEmpty()) SystemVoice(engine, v) { v = it }

                TextButton(
                    modifier = Modifier.padding(top = 10.dp),
                    onClick = {
                        found = "Reading the stored chapters…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                VoicingDetector.detect(Library.store.storedSample(bookId, DETECT_CHAPTERS))
                            }
                            if (result.chapters == 0) {
                                found = "No chapters are stored yet. Open or download a few, then try again."
                                return@launch
                            }
                            v = v.copy(
                                quotes = result.suggestedQuotes,
                                stutters = result.suggestedStutters,
                                system = result.suggestedSystem,
                            )
                            found = summary(result)
                        }
                    },
                ) { Text("Detect from stored chapters") }
                found?.let { Note(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                NovelVoicings.set(bookId, v)
                TtsController.applySettings()
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { v = NovelVoicing(); found = null }, enabled = !v.isDefault) {
                    Text("Reset")
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun DialogueVoice(engine: String, v: NovelVoicing, onChange: (NovelVoicing) -> Unit) {
    when (engine) {
        ReaderPrefs.ENGINE_KOKORO -> {
            val narrator by ReaderPrefs.kokoroSpeaker.collectAsState()
            val general by ReaderPrefs.kokoroDialogueSpeaker.collectAsState()
            val inherited = general.takeIf { KokoroVoices.voice(it) != null } ?: ExpressiveReading.kokoroPartner(narrator)
            Heading("Dialogue voice")
            KokoroVoicePicker(
                currentId = v.kokoroDialogue ?: -1,
                label = "Voice",
                automatic = "As in Settings · " + KokoroVoices.describe(inherited),
            ) { onChange(v.copy(kokoroDialogue = it.takeIf { id -> id >= 0 })) }
        }
        ReaderPrefs.ENGINE_SUPERTONIC -> {
            val narrator by ReaderPrefs.supertonicSpeaker.collectAsState()
            val general by ReaderPrefs.supertonicDialogueSpeaker.collectAsState()
            val voices = TtsModels.SUPERTONIC_VOICES
            val inherited = general.takeIf { it in voices.indices } ?: ExpressiveReading.supertonicPartner(narrator)
            Heading("Dialogue voice")
            SupertonicVoicePicker(
                currentId = v.supertonicDialogue ?: -1,
                label = "Voice",
                automatic = "As in Settings · " + voices.getOrElse(inherited) { "" },
            ) { onChange(v.copy(supertonicDialogue = it.takeIf { id -> id >= 0 })) }
        }
        ReaderPrefs.ENGINE_PIPER -> Note("Piper has one voice per model, so dialogue keeps the narrator's voice.")
        else -> {}
    }
}

@Composable
private fun SystemVoice(engine: String, v: NovelVoicing, onChange: (NovelVoicing) -> Unit) {
    when (engine) {
        ReaderPrefs.ENGINE_KOKORO -> {
            val narrator by ReaderPrefs.kokoroSpeaker.collectAsState()
            val auto = ExpressiveReading.kokoroSystemVoice(narrator, ExpressiveReading.dialogueVoice(v, engine, narrator))
            KokoroVoicePicker(
                currentId = v.kokoroSystem ?: -1,
                label = "System voice",
                automatic = "Automatic · " + KokoroVoices.describe(auto),
            ) { onChange(v.copy(kokoroSystem = it.takeIf { id -> id >= 0 })) }
        }
        ReaderPrefs.ENGINE_SUPERTONIC -> {
            val narrator by ReaderPrefs.supertonicSpeaker.collectAsState()
            val voices = TtsModels.SUPERTONIC_VOICES
            SupertonicVoicePicker(
                currentId = v.supertonicSystem ?: -1,
                label = "System voice",
                automatic = "Automatic · " + voices.getOrElse(ExpressiveReading.supertonicSystemVoice(narrator)) { "" },
            ) { onChange(v.copy(supertonicSystem = it.takeIf { id -> id >= 0 })) }
        }
        ReaderPrefs.ENGINE_PIPER -> Note("Piper has one voice per model, so system messages keep the narrator's voice.")
        else -> {}
    }
}

/** "From 40 chapters: “…” 1,203 · ‘…’ 12. Stutters: V–Violet 9." */
private fun summary(found: VoicingDetector.Found): String {
    val quotes = found.quotes.entries.sortedByDescending { it.value }
        .joinToString(" · ") { (q, n) -> "${q.label} ${"%,d".format(n)}" }.ifEmpty { "no quote marks" }
    val stutters = found.stutters.filterValues { it > 0 }.entries.sortedByDescending { it.value }
        .joinToString(" · ") { (m, n) -> "${m.label} $n" }.ifEmpty { "none found, so all marks stay on" }
    val system = found.system.entries.sortedByDescending { it.value }
        .joinToString(" · ") { (m, n) -> "${m.label} $n" }.ifEmpty { "none" }
    val chapters = found.chapters
    return "From $chapters stored chapter${if (chapters == 1) "" else "s"}: $quotes. Stutters: $stutters. " +
        "Bracketed: $system."
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
