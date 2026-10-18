package com.novelscraper.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.tts.ExpressiveReading
import com.novelscraper.app.tts.KokoroVoices
import com.novelscraper.app.tts.TtsController
import com.novelscraper.app.tts.TtsModels

/**
 * Settings → Narration → Expressive reading (beta, desktop only): the switch,
 * and the voice dialogue is read in.
 */
@Composable
fun ExpressiveReadingSetting() {
    val engine by ReaderPrefs.ttsEngine.collectAsState()
    if (engine == ReaderPrefs.ENGINE_DEVICE) return
    val on by ReaderPrefs.ttsExpressive.collectAsState()
    val kokoroSpeaker by ReaderPrefs.kokoroSpeaker.collectAsState()
    val supertonicSpeaker by ReaderPrefs.supertonicSpeaker.collectAsState()
    val kokoroDialogue by ReaderPrefs.kokoroDialogueSpeaker.collectAsState()
    val supertonicDialogue by ReaderPrefs.supertonicDialogueSpeaker.collectAsState()

    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Expressive reading (beta)", style = MaterialTheme.typography.bodyMedium)
            Text("Quoted speech in a second voice, with pauses and pacing that follow the text. " +
                "It doesn't add emotion: the voices can't be told how to feel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = {
            ReaderPrefs.setTtsExpressive(it)
            TtsController.applySettings()
        })
    }
    if (!on) return

    when (engine) {
        ReaderPrefs.ENGINE_KOKORO -> KokoroVoicePicker(
            currentId = kokoroDialogue,
            label = "Dialogue voice",
            automatic = "Automatic · " + KokoroVoices.describe(ExpressiveReading.kokoroPartner(kokoroSpeaker)),
        ) {
            ReaderPrefs.setKokoroDialogueSpeaker(it)
            TtsController.applySettings()
        }
        ReaderPrefs.ENGINE_SUPERTONIC -> SupertonicVoicePicker(
            currentId = supertonicDialogue,
            label = "Dialogue voice",
            automatic = "Automatic · " + TtsModels.SUPERTONIC_VOICES
                .getOrElse(ExpressiveReading.supertonicPartner(supertonicSpeaker)) { "" },
        ) {
            ReaderPrefs.setSupertonicDialogueSpeaker(it)
            TtsController.applySettings()
        }
        else -> Text("Piper has one voice per model, so dialogue keeps the narrator's voice; " +
            "only the pauses and pacing change.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
    }
}
