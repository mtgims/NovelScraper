package com.novelscraper.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.tts.GpuVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Narration → GPU acceleration: what this machine's graphics card
 * can do for the neural voices, the pack that makes it possible, and the switch.
 */
@Composable
fun GpuAccelerationSetting() {
    val engine by ReaderPrefs.ttsEngine.collectAsState()
    if (engine != ReaderPrefs.ENGINE_KOKORO && engine != ReaderPrefs.ENGINE_PIPER) return

    // Asking the driver runs a program, so not on the UI's thread.
    val support by produceState<GpuVoice.Support?>(null) {
        value = withContext(Dispatchers.IO) { GpuVoice.support }
    }
    val state by GpuVoice.state.collectAsState()
    val enabled by GpuVoice.enabled.collectAsState()
    var installed by remember { mutableStateOf(GpuVoice.installed) }
    var removalPending by remember { mutableStateOf(GpuVoice.removalPending) }
    val scope = rememberCoroutineScope()

    Text("GPU acceleration", style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))

    @Composable
    fun note(text: String, error: Boolean = false) = Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    when (val s = support) {
        null -> note("Looking for a graphics card…")
        GpuVoice.Support.None -> note("No graphics card narration can use was found, so it runs on the processor.")
        is GpuVoice.Support.Unsuitable -> note("${s.card.name}: ${s.why} Narration runs on the processor.")
        is GpuVoice.Support.Ready -> when (val st = state) {
            is GpuVoice.State.Downloading -> {
                note("Downloading ${st.what} · ${st.bytes / 1_000_000} / ${st.total / 1_000_000} MB")
                LinearProgressIndicator(
                    progress = { (st.bytes.toFloat() / st.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                TextButton(onClick = { GpuVoice.cancel() }) { Text("Cancel") }
            }
            is GpuVoice.State.Unpacking -> {
                note("Unpacking ${st.what}…")
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            else -> if (installed) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    // DirectML picks the card itself, so it is only named where
                    // the choice is certain.
                    val label = if (s.backend == GpuVoice.Backend.CUDA) "Narrate on the ${s.card.name}"
                                else "Narrate on the graphics card (DirectML)"
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { GpuVoice.setEnabled(it) })
                }
                val problem = GpuVoice.problem
                when {
                    problem != null -> note(problem, error = true)
                    enabled && GpuVoice.active -> note("Narration is running on the graphics card.")
                    enabled && !GpuVoice.takesEffectNow -> note("Takes effect the next time NovelScraper starts.")
                    !enabled && GpuVoice.active -> note("Narration stays on the graphics card until NovelScraper restarts.")
                }
                if (removalPending) {
                    note("The GPU files are removed the next time NovelScraper starts.")
                } else {
                    TextButton(onClick = {
                        GpuVoice.remove()
                        installed = GpuVoice.installed
                        removalPending = GpuVoice.removalPending
                    }) {
                        Text("Remove GPU files (${"%.1f".format(GpuVoice.installedBytes / 1e9)} GB)")
                    }
                }
            } else {
                note(
                    if (s.backend == GpuVoice.Backend.CUDA)
                        "The ${s.card.name} can narrate several times faster than the processor, " +
                            "leaving the processor nearly idle. This downloads NVIDIA's CUDA libraries: " +
                            "about ${"%.1f".format(GpuVoice.downloadBytes / 1e9)} GB, 2.6 GB once unpacked."
                    else
                        "Narration can run on the graphics card through DirectML, which takes most of " +
                            "the work off the processor. This downloads about " +
                            "${GpuVoice.downloadBytes / 1_000_000} MB.",
                )
                (st as? GpuVoice.State.Failed)?.let { note("Download failed: ${it.message}", error = true) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        scope.launch(Dispatchers.IO) {
                            GpuVoice.download()
                            installed = GpuVoice.installed
                        }
                    }) { Text("Download and turn on") }
                }
            }
        }
    }
}
