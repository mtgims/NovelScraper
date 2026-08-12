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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.net.Net
import com.novelscraper.app.ui.theme.Kicker
import com.novelscraper.app.ui.theme.THEMES
import com.novelscraper.app.ui.theme.ThemeController

@Composable
fun SettingsScreen(username: String, onLogout: () -> Unit) {
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

        Section("READING")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Font size  ·  ${(fontScale * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { ReaderPrefs.decreaseFont() }) { Text("A-") }
            Text("  ")
            OutlinedButton(onClick = { ReaderPrefs.increaseFont() }) { Text("A+") }
        }

        Section("ACCOUNT")
        Text(username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(Net.baseUrl, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onLogout, modifier = Modifier.padding(top = 16.dp)) { Text("Sign out") }
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
