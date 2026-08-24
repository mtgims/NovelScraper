package com.novelscraper.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelscraper.app.ui.ImportUi
import com.novelscraper.app.ui.ImportViewModel
import com.novelscraper.app.ui.NewScrapeViewModel
import com.novelscraper.app.ui.ScrapeUi

@Composable
fun NewScrapeScreen(
    onScraped: () -> Unit,
    onImported: () -> Unit,
    onAddFromNu: (String?) -> Unit = {},
) {
    val vm: NewScrapeViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val importVm: ImportViewModel = viewModel()
    val importUi by importVm.ui.collectAsState()
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) importVm.importEpubs(uris) }

    LaunchedEffect(importUi) {
        if (importUi is ImportUi.Done) { importVm.reset(); onImported() }
    }

    var url by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var cpv by remember { mutableStateOf("") }
    var delay by remember { mutableStateOf("") }
    var conc by remember { mutableStateOf("") }

    LaunchedEffect(ui) {
        when (val s = ui) {
            is ScrapeUi.Done -> { vm.reset(); onScraped() }
            is ScrapeUi.NeedsNuLogin -> { vm.reset(); onAddFromNu(s.url) }  // fall back to the visible browser
            else -> {}
        }
    }

    val submitting = ui is ScrapeUi.Submitting

    Column(
        Modifier.fillMaxWidth().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 120.dp),
    ) {
        Text("New Scrape", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Paste a novel URL from a supported source.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Novel URL") }, singleLine = true, enabled = !submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.padding(top = 4.dp)) {
            Text(if (advanced) "Hide options" else "Options")
        }
        if (advanced) {
            OutlinedTextField(
                value = cpv, onValueChange = { cpv = it.filter(Char::isDigit) },
                label = { Text("Chapters per volume") }, singleLine = true, enabled = !submitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = delay, onValueChange = { delay = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Delay (s)") }, singleLine = true, enabled = !submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Text("  ")
                OutlinedTextField(
                    value = conc, onValueChange = { conc = it.filter(Char::isDigit) },
                    label = { Text("Concurrency") }, singleLine = true, enabled = !submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        (ui as? ScrapeUi.Error)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }

        Button(
            onClick = {
                vm.scrape(url, cpv.toIntOrNull(), delay.toFloatOrNull(), conc.toIntOrNull())
            },
            enabled = !submitting && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) {
            if (submitting) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
            Text("Scrape")
        }

        OutlinedButton(
            onClick = { onAddFromNu(null) },
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            Text("Browse NovelUpdates")
        }
        Text(
            "Or just paste a NovelUpdates link above — you'll pick the translation group.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        HorizontalDivider(Modifier.padding(vertical = 24.dp))

        Text("Import EPUB", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add books from EPUB files (e.g. scraped on another device).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        val importing = importUi is ImportUi.Uploading
        (importUi as? ImportUi.Error)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        }
        OutlinedButton(
            onClick = { picker.launch("application/epub+zip") },
            enabled = !importing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (importing) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
            Text(if (importing) "Importing…" else "Choose EPUB files")
        }
    }

    (ui as? ScrapeUi.ChooseNu)?.let { state ->
        AlertDialog(
            onDismissRequest = { vm.reset() },
            title = { Text(state.series.title.ifBlank { "Choose a translation" }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Pick a group — it scrapes that site from chapter 1:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    state.series.groups.forEach { g ->
                        val upto = if (g.latestLabel.isNotBlank()) "  ·  up to ${g.latestLabel}" else ""
                        TextButton(onClick = { vm.pickNuGroup(state.series, g) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${g.name}$upto", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.reset() }) { Text("Cancel") } },
        )
    }
}
