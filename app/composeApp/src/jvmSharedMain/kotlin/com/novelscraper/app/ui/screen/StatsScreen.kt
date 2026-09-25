package com.novelscraper.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelscraper.app.data.StatsRead
import com.novelscraper.app.platform.SaveTarget
import com.novelscraper.app.platform.rememberFileSaver
import com.novelscraper.app.platform.showToast
import com.novelscraper.app.ui.StatsUi
import com.novelscraper.app.ui.StatsViewModel
import com.novelscraper.app.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun StatsScreen() {
    val vm: StatsViewModel = viewModel { StatsViewModel() }
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var chooser by remember { mutableStateOf(false) }

    fun save(target: SaveTarget?, format: String) {
        if (target == null) return
        scope.launch {
            val bytes = vm.export(format)
            if (bytes == null) {
                showToast("Export failed — is the server reachable?", long = true)
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { target.write(bytes) }
            showToast(if (ok) "Progress exported" else "Couldn't write the file", long = true)
        }
    }
    val saveCsv = rememberFileSaver("text/csv") { save(it, "csv") }
    val saveJson = rememberFileSaver("application/json") { save(it, "json") }
    val date = LocalDate.now().toString()

    Box(Modifier.fillMaxSize()) {
        when (val s = ui) {
            is StatsUi.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is StatsUi.Error -> Text(s.message, Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error)
            is StatsUi.Data -> StatsContent(s.stats, onExport = { chooser = true })
        }
    }

    if (chooser) {
        AlertDialog(
            onDismissRequest = { chooser = false },
            title = { Text("Download reading progress") },
            text = { Text("Save a file listing each novel and where you are. Pick a format.") },
            confirmButton = {
                TextButton(onClick = {
                    chooser = false; saveCsv("novelscraper-progress-$date.csv")
                }) { Text("CSV") }
            },
            dismissButton = {
                TextButton(onClick = {
                    chooser = false; saveJson("novelscraper-progress-$date.json")
                }) { Text("JSON") }
            },
        )
    }
}

@Composable
private fun StatsContent(s: StatsRead, onExport: (() -> Unit)?) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Statistics", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f))
                if (onExport != null) OutlinedButton(onClick = onExport) { Text("Export") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("BOOKS", s.total_books.toString(), Modifier.weight(1f))
                Tile("FINISHED", s.books_finished.toString(), Modifier.weight(1f))
                Tile("READING", "${s.percent_read.toInt()}%", Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("CHAPTERS", "${s.chapters_read}/${s.total_chapters}", Modifier.weight(1f))
                Tile("WORDS READ", compact(s.words_read), Modifier.weight(1f))
                Tile("HOURS READ", "%.1f".format(s.hours_read), Modifier.weight(1f))
            }
        }
        if (s.books.isNotEmpty()) {
            item {
                Text("BY BOOK", style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp))
            }
            items(s.books, key = { it.book_id }) { b ->
                Column(Modifier.fillMaxWidth()) {
                    Text(b.title, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { (b.percent_read / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.weight(1f),
                        )
                        Text("  ${b.read_count}/${b.total_chapters}",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFamily = Mono),
                fontWeight = FontWeight.Medium)
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000 -> "%.1fk".format(n / 1_000f)
    else -> n.toString()
}
