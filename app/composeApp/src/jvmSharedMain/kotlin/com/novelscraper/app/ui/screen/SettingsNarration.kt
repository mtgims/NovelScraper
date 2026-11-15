package com.novelscraper.app.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.platform.hasSystemTts
import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.tts.KokoroDownloader
import com.novelscraper.app.tts.KokoroVoices
import com.novelscraper.app.tts.ModelDownloads
import com.novelscraper.app.tts.running
import com.novelscraper.app.tts.NarrationText
import com.novelscraper.app.tts.TtsModels
import com.novelscraper.app.ui.components.KokoroVoicePicker
import com.novelscraper.app.ui.components.SupertonicVoicePicker

/** An engine to choose, and a line on what it is like. */
private class Engine(val id: String, val name: String, val about: String)

private fun engines(): List<Engine> = listOfNotNull(
    if (hasSystemTts) Engine(ReaderPrefs.ENGINE_DEVICE, "Device", "The voice built into this phone. Nothing to download.") else null,
    Engine(
        ReaderPrefs.ENGINE_KOKORO, "Kokoro",
        if (isDesktop) "The most natural, in many languages. 335 MB." else "The most natural, in many languages. 125 MB.",
    ),
    Engine(ReaderPrefs.ENGINE_SUPERTONIC, "Supertonic", "Nearly as natural and several times faster. English. 122 MB."),
    Engine(ReaderPrefs.ENGINE_PIPER, "Piper", "Fast English voices, 65 MB each."),
)

/** "Kokoro · Michael · 1.0×": the engine, its voice, and the speed. */
@Composable
internal fun narrationSummary(): String {
    val engine by ReaderPrefs.ttsEngine.collectAsState()
    val rate by ReaderPrefs.ttsRate.collectAsState()
    val kokoro by ReaderPrefs.kokoroSpeaker.collectAsState()
    val supertonic by ReaderPrefs.supertonicSpeaker.collectAsState()
    val piper by ReaderPrefs.piperVoice.collectAsState()
    val downloads by ModelDownloads.progress.collectAsState()
    val ready = remember(engine, piper, downloads) {
        TtsModels.isModelReady(if (engine == ReaderPrefs.ENGINE_PIPER) piper else engine)
    }
    val name = engines().firstOrNull { it.id == engine }?.name ?: engine
    val voice = when {
        engine == ReaderPrefs.ENGINE_DEVICE -> null
        !ready -> "not downloaded"
        engine == ReaderPrefs.ENGINE_KOKORO -> KokoroVoices.voice(kokoro)?.name
        engine == ReaderPrefs.ENGINE_SUPERTONIC -> TtsModels.SUPERTONIC_VOICES.getOrNull(supertonic)
        else -> TtsModels.PIPER_VOICES.firstOrNull { it.id == piper }?.name
    }
    return listOfNotNull(name, voice, "${"%.1f".format(rate)}×").joinToString(" · ")
}

@Composable
internal fun NarrationPage() {
    val engine by ReaderPrefs.ttsEngine.collectAsState()
    val rate by ReaderPrefs.ttsRate.collectAsState()

    SettingsGroup(title = "Engine") {
        engines().forEachIndexed { i, e ->
            if (i > 0) GroupDivider(inset = 58.dp)
            SettingsRow(
                title = e.name,
                summary = e.about,
                selected = false,
                onClick = { ReaderPrefs.setTtsEngine(e.id) },
                leading = { RadioButton(selected = engine == e.id, onClick = { ReaderPrefs.setTtsEngine(e.id) }) },
            )
        }
    }

    when (engine) {
        ReaderPrefs.ENGINE_KOKORO, ReaderPrefs.ENGINE_SUPERTONIC -> SettingsGroup(title = "Voice") { NeuralModel(engine) }
        ReaderPrefs.ENGINE_PIPER -> SettingsGroup(title = "Voice") { PiperVoiceList() }
    }

    SettingsGroup(title = "Speed") {
        GroupBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speech rate", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("${"%.1f".format(rate)}×", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = rate, onValueChange = { ReaderPrefs.setTtsRate(it) },
                valueRange = 0.5f..2.5f,
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                Text("0.5×", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("2.5×", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    SettingsHooks.narration?.let { extra ->
        SettingsGroup(title = "On this computer") { GroupBody { extra() } }
    }

    NarrationWords()
}

/** Piper voices, grouped by accent. Tap a downloaded voice to use it; tap an
 *  un-downloaded one to fetch it (~65 MB each) and then use it. */
@Composable
private fun PiperVoiceList() {
    val selected by ReaderPrefs.piperVoice.collectAsState()
    val downloads by ModelDownloads.progress.collectAsState()
    val busy = downloads.values.any { it.running }

    TtsModels.PIPER_VOICES.groupBy { it.accent }.forEach { (accent, voices) ->
        GroupSubhead(accent)
        voices.forEach { v ->
            val progress = downloads[v.id]
            val installed = remember(downloads, v.id) { TtsModels.isModelReady(v.id) }
            val fetching = progress?.running == true
            val status = when (progress) {
                is KokoroDownloader.Progress.Downloading ->
                    "Downloading ${progress.bytes / 1_000_000} / ${progress.total / 1_000_000} MB"
                KokoroDownloader.Progress.Extracting -> "Extracting…"
                is KokoroDownloader.Progress.Failed -> if (installed) null else "Download failed: ${progress.message}"
                else -> null
            }
            SettingsRow(
                title = v.name,
                summary = status ?: v.gender,
                summaryColor = if (progress is KokoroDownloader.Progress.Failed && !installed)
                    MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                enabled = installed || !busy,
                onClick = {
                    if (installed) ReaderPrefs.setPiperVoice(v.id)
                    else ModelDownloads.start(v.id) { ReaderPrefs.setPiperVoice(v.id) }
                },
                trailing = {
                    when {
                        fetching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        installed && v.id == selected ->
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        installed -> Text("Use", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        else -> Icon(Icons.Outlined.Download, contentDescription = "Download",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    }
                },
            )
        }
    }
    Text("Each voice is about 65 MB and downloads when first chosen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp))
}

/** Download state + (for Kokoro) the voice picker, for one neural engine. */
@Composable
private fun NeuralModel(engine: String) {
    val speaker by ReaderPrefs.kokoroSpeaker.collectAsState()
    val supertonicSpeaker by ReaderPrefs.supertonicSpeaker.collectAsState()
    val downloads by ModelDownloads.progress.collectAsState()
    val progress = downloads[engine]
    val installed = remember(engine, downloads) { TtsModels.isModelReady(engine) }

    val blurb = when (engine) {
        ReaderPrefs.ENGINE_KOKORO ->
            if (isDesktop) "~335 MB · multilingual, most natural (English, Spanish, French, Chinese, Japanese…), " +
                "at full precision. About real time."
            else "~125 MB · multilingual, most natural (English, Spanish, French, Chinese, Japanese…). ~1× real time."
        else ->
            "~122 MB · ten voices, nearly as natural as Kokoro and several times faster, light on the " +
                "processor: made for phones. Reads English for now."
    }

    GroupBody {
        when {
            progress?.running == true -> {
                val label = when (progress) {
                    is KokoroDownloader.Progress.Downloading ->
                        "Downloading  ${progress.bytes / 1_000_000} / ${progress.total / 1_000_000} MB"
                    else -> "Extracting…"
                }
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                val frac = (progress as? KokoroDownloader.Progress.Downloading)
                    ?.let { it.bytes.toFloat() / it.total.coerceAtLeast(1) }
                if (frac != null) {
                    LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text("It carries on if you leave this page.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            installed -> {
                if (engine == ReaderPrefs.ENGINE_KOKORO) {
                    KokoroVoicePicker(currentId = speaker) { ReaderPrefs.setKokoroSpeaker(it) }
                } else {
                    SupertonicVoicePicker(currentId = supertonicSpeaker) { ReaderPrefs.setSupertonicSpeaker(it) }
                }
                Text("Voice model downloaded.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            else -> {
                (progress as? KokoroDownloader.Progress.Failed)?.let {
                    Text("Download failed: ${it.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                Text(blurb, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp))
                val busy = downloads.values.any { it.running }
                Button(
                    onClick = { ModelDownloads.start(engine) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Another voice is downloading" else "Download voice model") }
            }
        }
    }
}

/**
 * What narration says: the asides it passes over (site plugs, patron links,
 * translator notes, plus the reader's own lines) and how particular words should
 * sound.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NarrationWords() {
    val skipJunk by ReaderPrefs.ttsSkipJunk.collectAsState()
    val patterns by ReaderPrefs.ttsJunkPatterns.collectAsState()
    val terms by ReaderPrefs.ttsDictionary.collectAsState()
    var addPattern by remember { mutableStateOf(false) }
    var addTerm by remember { mutableStateOf<NarrationText.Term?>(null) }

    SettingsGroup(title = "What's read aloud") {
        SettingsRow(
            title = "Skip asides",
            summary = "Site plugs, patron links and translator notes aren't read aloud.",
            onClick = { ReaderPrefs.setTtsSkipJunk(!skipJunk) },
            trailing = { Switch(checked = skipJunk, onCheckedChange = { ReaderPrefs.setTtsSkipJunk(it) }) },
        )
        if (skipJunk) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            ) {
                patterns.forEach { p ->
                    InputChip(
                        selected = false,
                        onClick = { ReaderPrefs.setTtsJunkPatterns(patterns - p) },
                        label = { Text(p) },
                        trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) },
                    )
                }
                AssistChip(
                    onClick = { addPattern = true },
                    label = { Text("Add a line to skip") },
                    leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
            }
        }
        GroupDivider()
        SettingsRow(title = "Pronunciation", summary = "Say a word the way you want to hear it.")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        ) {
            terms.forEach { t ->
                InputChip(
                    selected = false,
                    onClick = { addTerm = t },
                    label = { Text("${t.from} → ${t.to}") },
                    trailingIcon = {
                        Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp).clickable {
                            ReaderPrefs.setTtsDictionary(terms - t)
                        })
                    },
                )
            }
            AssistChip(
                onClick = { addTerm = NarrationText.Term("", "") },
                label = { Text("Add a word") },
                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }

    if (addPattern) {
        TextPrompt(
            title = "Skip lines containing",
            label = "Text",
            initial = "",
            onDone = { text ->
                if (text.isNotBlank()) ReaderPrefs.setTtsJunkPatterns(patterns + text.trim())
                addPattern = false
            },
            onDismiss = { addPattern = false },
        )
    }
    addTerm?.let { editing ->
        TermPrompt(
            term = editing,
            onDone = { t ->
                ReaderPrefs.setTtsDictionary(terms.filter { it.from != editing.from && it.from != t.from } + t)
                addTerm = null
            },
            onDismiss = { addTerm = null },
        )
    }
}

@Composable
private fun TextPrompt(
    title: String,
    label: String,
    initial: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(label) })
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks how a word should be said; also used for a novel's own words (desktop). */
@Composable
internal fun TermPrompt(
    term: NarrationText.Term,
    onDone: (NarrationText.Term) -> Unit,
    onDismiss: () -> Unit,
) {
    var from by remember { mutableStateOf(term.from) }
    var to by remember { mutableStateOf(term.to) }
    var whole by remember { mutableStateOf(term.wholeWord) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How to say a word") },
        text = {
            Column {
                OutlinedTextField(value = from, onValueChange = { from = it }, singleLine = true,
                    label = { Text("Written") })
                OutlinedTextField(value = to, onValueChange = { to = it }, singleLine = true,
                    label = { Text("Said as") }, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Whole word only", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Switch(checked = whole, onCheckedChange = { whole = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDone(NarrationText.Term(from.trim(), to.trim(), whole)) },
                enabled = from.isNotBlank() && to.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
