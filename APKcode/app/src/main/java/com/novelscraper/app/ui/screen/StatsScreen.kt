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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelscraper.app.data.StatsRead
import com.novelscraper.app.ui.StatsUi
import com.novelscraper.app.ui.StatsViewModel
import com.novelscraper.app.ui.theme.Mono

@Composable
fun StatsScreen() {
    val vm: StatsViewModel = viewModel()
    val ui by vm.ui.collectAsState()

    Box(Modifier.fillMaxSize()) {
        when (val s = ui) {
            is StatsUi.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is StatsUi.Error -> Text(s.message, Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error)
            is StatsUi.Data -> StatsContent(s.stats)
        }
    }
}

@Composable
private fun StatsContent(s: StatsRead) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Statistics", style = MaterialTheme.typography.headlineMedium) }
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
