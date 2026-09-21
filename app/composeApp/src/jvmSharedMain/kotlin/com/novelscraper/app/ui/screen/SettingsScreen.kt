package com.novelscraper.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.net.Account
import com.novelscraper.app.net.Net
import com.novelscraper.app.platform.hasSystemTts
import com.novelscraper.app.tts.KokoroDownloader
import com.novelscraper.app.tts.TtsModels
import com.novelscraper.app.ui.components.KokoroVoicePicker
import com.novelscraper.app.ui.theme.Kicker
import com.novelscraper.app.ui.theme.THEMES
import com.novelscraper.app.ui.theme.ThemeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onSignIn: () -> Unit, onLogout: () -> Unit) {
    val account by Account.state.collectAsState()
    val theme by ThemeController.theme.collectAsState()
    val rate by ReaderPrefs.ttsRate.collectAsState()
    val fontScale by ReaderPrefs.fontScale.collectAsState()

    Column(
        Modifier.fillMaxWidth().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 120.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Section("APPEARANCE")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            THEMES.forEach { t ->
                val selected = t.name == theme
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(t.swatchBg)
                            .border(
                                BorderStroke(if (selected) 3.dp else 1.dp,
                                    if (selected) t.accent else MaterialTheme.colorScheme.outline),
                                CircleShape,
                            )
                            .clickable { ThemeController.setTheme(t.name) },
                    ) {}
                    Text(t.label, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        Section("NARRATION")
        Text("Speech rate  ·  ${"%.1f".format(rate)}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = rate, onValueChange = { ReaderPrefs.setTtsRate(it) },
            valueRange = 0.5f..2.5f,
            modifier = Modifier.fillMaxWidth(),
        )
        NarrationEngine()

        Section("READING")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Font size  ·  ${(fontScale * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { ReaderPrefs.decreaseFont() }) { Text("A-") }
            Text("  ")
            OutlinedButton(onClick = { ReaderPrefs.increaseFont() }) { Text("A+") }
        }

        Section("SERVER ACCOUNT")
        when (val a = account) {
            is Account.State.SignedIn -> {
                Text(a.username.ifBlank { "Signed in" }, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(Net.baseUrl + if (a.online) "" else " · not reached yet", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onLogout, modifier = Modifier.padding(top = 16.dp)) { Text("Sign out") }
            }
            Account.State.SignedOut -> {
                Text(
                    "Optional. Signed in to a NovelScraper server, its library comes into yours and " +
                        "stays in step, and Scrape, Progress and Stats work.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onSignIn, modifier = Modifier.padding(top = 16.dp)) { Text("Sign in") }
            }
        }
    }
}

/**
 * Engine picker (device / on-device Kokoro / on-device Piper) plus each neural
 * engine's model download. Downloading runs off the main thread; progress is
 * mirrored back to Compose state.
 */
@Composable
private fun NarrationEngine() {
    val engine by ReaderPrefs.ttsEngine.collectAsState()

    Text("Engine", style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hasSystemTts) {
            EngineChip("Device", engine == ReaderPrefs.ENGINE_DEVICE) {
                ReaderPrefs.setTtsEngine(ReaderPrefs.ENGINE_DEVICE)
            }
        }
        EngineChip("Kokoro", engine == ReaderPrefs.ENGINE_KOKORO) {
            ReaderPrefs.setTtsEngine(ReaderPrefs.ENGINE_KOKORO)
        }
        EngineChip("Piper", engine == ReaderPrefs.ENGINE_PIPER) {
            ReaderPrefs.setTtsEngine(ReaderPrefs.ENGINE_PIPER)
        }
    }

    when (engine) {
        ReaderPrefs.ENGINE_KOKORO -> NeuralModel(ReaderPrefs.ENGINE_KOKORO)
        ReaderPrefs.ENGINE_PIPER -> PiperVoiceList()
    }
}

/** Piper voices, grouped by accent. Tap a downloaded voice to use it; tap an
 *  un-downloaded one to fetch it (~65 MB each) and then use it. */
@Composable
private fun PiperVoiceList() {
    val scope = rememberCoroutineScope()
    val selected by ReaderPrefs.piperVoice.collectAsState()
    var busyId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<KokoroDownloader.Progress?>(null) }
    var tick by remember { mutableStateOf(0) } // bump to re-check installed after a download

    Text("Fast English voices · ~65 MB each, downloaded on demand.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp))

    TtsModels.PIPER_VOICES.groupBy { it.accent }.forEach { (accent, voices) ->
        Text(accent.uppercase(),
            style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
        voices.forEach { v ->
            val installed = remember(tick, v.id) { TtsModels.isModelReady(v.id) }
            val busy = busyId == v.id
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = busyId == null) {
                        if (installed) {
                            ReaderPrefs.setPiperVoice(v.id)
                        } else {
                            busyId = v.id
                            progress = KokoroDownloader.Progress.Downloading(0, 1)
                            scope.launch(Dispatchers.IO) {
                                KokoroDownloader.download(Net.client, v.id) { p ->
                                    scope.launch(Dispatchers.Main) {
                                        progress = p
                                        when (p) {
                                            is KokoroDownloader.Progress.Done -> {
                                                busyId = null; tick++; ReaderPrefs.setPiperVoice(v.id)
                                            }
                                            is KokoroDownloader.Progress.Failed -> busyId = null
                                            else -> {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${v.name} · ${v.gender}", style = MaterialTheme.typography.bodyMedium)
                    if (busy) {
                        val p = progress
                        val lbl = when (p) {
                            is KokoroDownloader.Progress.Downloading ->
                                "Downloading ${p.bytes / 1_000_000} / ${p.total / 1_000_000} MB"
                            is KokoroDownloader.Progress.Extracting -> "Extracting…"
                            else -> "…"
                        }
                        Text(lbl, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when {
                    busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    installed && v.id == selected ->
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    installed ->
                        Text("Use", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    else ->
                        Text("Download", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    (progress as? KokoroDownloader.Progress.Failed)?.let {
        Text("Download failed: ${it.message}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Download state + (for Kokoro) the voice picker, for one neural engine. */
@Composable
private fun NeuralModel(engine: String) {
    val scope = rememberCoroutineScope()
    val speaker by ReaderPrefs.kokoroSpeaker.collectAsState()

    var installed by remember(engine) { mutableStateOf(TtsModels.isModelReady(engine)) }
    var progress by remember(engine) { mutableStateOf<KokoroDownloader.Progress?>(null) }
    val downloading = progress is KokoroDownloader.Progress.Downloading ||
        progress is KokoroDownloader.Progress.Extracting

    val blurb = if (engine == ReaderPrefs.ENGINE_KOKORO)
        "~125 MB · multilingual, most natural (English, Spanish, French, Chinese, Japanese…). ~1× real time."
    else
        "~65 MB · fast English voice (Amy) — several times real time, no buffering; less expressive than Kokoro."

    when {
        downloading -> {
            val p = progress
            val label = when (p) {
                is KokoroDownloader.Progress.Downloading ->
                    "Downloading  ${p.bytes / 1_000_000} / ${p.total / 1_000_000} MB"
                else -> "Extracting…"
            }
            Text(label, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            val frac = (progress as? KokoroDownloader.Progress.Downloading)
                ?.let { it.bytes.toFloat() / it.total.coerceAtLeast(1) }
            if (frac != null) {
                LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        installed -> {
            Text("Voice model installed", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
            if (engine == ReaderPrefs.ENGINE_KOKORO) {
                KokoroVoicePicker(currentId = speaker) { ReaderPrefs.setKokoroSpeaker(it) }
            } else {
                Text("Voice · Amy (US English)", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        else -> {
            (progress as? KokoroDownloader.Progress.Failed)?.let {
                Text("Download failed: ${it.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp))
            }
            Text(blurb, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            Button(onClick = {
                progress = KokoroDownloader.Progress.Downloading(0, 1)
                scope.launch(Dispatchers.IO) {
                    KokoroDownloader.download(Net.client, engine) { p ->
                        scope.launch(Dispatchers.Main) {
                            progress = p
                            if (p is KokoroDownloader.Progress.Done) installed = true
                        }
                    }
                }
            }) { Text("Download voice model") }
        }
    }
}

@Composable
private fun EngineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
    )
}
