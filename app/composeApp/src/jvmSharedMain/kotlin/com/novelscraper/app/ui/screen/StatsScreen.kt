package com.novelscraper.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelscraper.app.library.NovelProgress
import com.novelscraper.app.library.ReadingStats
import com.novelscraper.app.library.WordSample
import com.novelscraper.app.platform.SaveTarget
import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.platform.rememberFileSaver
import com.novelscraper.app.platform.showToast
import com.novelscraper.app.ui.StatsUi
import com.novelscraper.app.ui.StatsViewModel
import com.novelscraper.app.ui.components.BookCover
import com.novelscraper.app.ui.components.ContentWidth
import com.novelscraper.app.ui.components.ScreenTitle
import com.novelscraper.app.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

@Composable
fun StatsScreen(onOpenNovel: (Int) -> Unit) {
    val vm: StatsViewModel = viewModel { StatsViewModel() }
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    val scope = rememberCoroutineScope()
    var chooser by remember { mutableStateOf(false) }

    fun save(target: SaveTarget?, format: String) {
        if (target == null) return
        scope.launch {
            val bytes = vm.export(format)
            if (bytes == null) {
                showToast("Couldn't put the statistics together. Try again.", long = true)
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { target.write(bytes) }
            showToast(if (ok) "Progress exported" else "Couldn't write the file", long = true)
        }
    }
    val saveCsv = rememberFileSaver("text/csv") { save(it, "csv") }
    val saveJson = rememberFileSaver("application/json") { save(it, "json") }
    val date = LocalDate.now().toString()

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Statistics") {
            if (ui is StatsUi.Data && (ui as StatsUi.Data).stats.novels > 0) {
                TextButton(onClick = { chooser = true }) {
                    Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp))
                    Text("Export", Modifier.padding(start = 6.dp))
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (val s = ui) {
                is StatsUi.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is StatsUi.Error -> Text(s.message, Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
                is StatsUi.Data ->
                    if (s.stats.novels == 0) EmptyStats(Modifier.align(Alignment.Center))
                    else StatsContent(s.stats, s.words, onOpenNovel)
            }
        }
    }

    if (chooser) {
        AlertDialog(
            onDismissRequest = { chooser = false },
            title = { Text("Download reading progress") },
            text = { Text("Save a file listing each novel and where you are. Pick a format.") },
            confirmButton = {
                TextButton(onClick = { chooser = false; saveCsv("novelscraper-progress-$date.csv") }) { Text("CSV") }
            },
            dismissButton = {
                TextButton(onClick = { chooser = false; saveJson("novelscraper-progress-$date.json") }) { Text("JSON") }
            },
        )
    }
}

/** Below this the page is one column, as on a phone; above it, cards sit side by side. */
private val WIDE = 840.dp
private val MAX_WIDTH = 1100.dp

private enum class Sort(val label: String) { Progress("Progress"), Recent("Recent"), Title("Title") }

@Composable
private fun StatsContent(s: ReadingStats, words: WordSample?, onOpenNovel: (Int) -> Unit) {
    var sort by remember { mutableStateOf(Sort.Progress) }
    val sorted = remember(s, sort) {
        when (sort) {
            Sort.Progress -> s.progress.sortedWith(compareByDescending<NovelProgress> { it.fraction }.thenByDescending { it.read })
            Sort.Recent -> s.progress.sortedByDescending { it.book.openedAt }
            Sort.Title -> s.progress.sortedBy { it.book.title.lowercase() }
        }
    }
    ContentWidth(max = MAX_WIDTH) {
        BoxWithConstraints {
            val wide = maxWidth >= WIDE
            val gap = if (wide) 20.dp else 14.dp
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (isDesktop) 32.dp else 110.dp),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                item(key = "hero") { HeroCard(s) }
                item(key = "tiles") { Tiles(s, words, columns = if (wide) 6 else 3) }
                if (s.continueReading.isNotEmpty()) item(key = "continue") {
                    ContinueReading(s.continueReading.take(12), onOpenNovel)
                }
                item(key = "charts") {
                    val sources: @Composable (Modifier) -> Unit = { m ->
                        RankedCard("Chapters read by source", "Where the reading comes from", s.bySource.take(6), m)
                    }
                    val genres: @Composable (Modifier) -> Unit = { m ->
                        RankedCard("Genres", "Novels in the library", s.byGenre.take(8), m)
                    }
                    val showSources = s.bySource.isNotEmpty()
                    val showGenres = s.byGenre.isNotEmpty()
                    if (wide && showSources && showGenres) {
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            sources(Modifier.weight(1f).fillMaxHeight())
                            genres(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            if (showSources) sources(Modifier.fillMaxWidth())
                            if (showGenres) genres(Modifier.fillMaxWidth())
                        }
                    }
                }
                if (s.rated > 0) item(key = "ratings") { RatingsCard(s) }
                item(key = "every-header") {
                    SectionTitle("Every novel", "${s.novels} in the library", Modifier.padding(start = 4.dp, top = 8.dp))
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Sort.entries.forEach { o ->
                            FilterChip(selected = sort == o, onClick = { sort = o }, label = { Text(o.label) })
                        }
                    }
                }
                val rows = if (wide) sorted.chunked(2) else sorted.map { listOf(it) }
                items(rows, key = { r -> "novel-" + r.first().book.id }) { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        r.forEach { p -> NovelRow(p, onOpenNovel, Modifier.weight(1f)) }
                        if (r.size == 1 && wide) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ---- Pieces --------------------------------------------------------------

@Composable
private fun StatCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = cardColor(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = modifier) {
        Box(Modifier.padding(20.dp)) { content() }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) Text(
            subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The four states a novel can be in, strongest for the furthest along; not started is gray. */
@Composable
private fun stateColors(): List<Color> {
    val c = MaterialTheme.colorScheme
    val base = cardColor()
    return listOf(c.primary, lerp(base, c.primary, 0.66f), lerp(base, c.primary, 0.38f), lerp(base, c.onSurfaceVariant, 0.3f))
}

/** Chapters read, as one big number, and every novel's state as one bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(s: ReadingStats) {
    val colors = stateColors()
    val states = listOf("Finished" to s.finished, "Caught up" to s.caughtUp, "Reading" to s.reading, "Not started" to s.notStarted)
    StatCard(Modifier.fillMaxWidth()) {
        Column {
            Text(
                "CHAPTERS READ", style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    grouped(s.chaptersRead),
                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
                    fontSize = 52.sp, lineHeight = 56.sp,
                )
                Text(
                    "of ${grouped(s.chapters)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, bottom = 9.dp),
                )
                Text(
                    percent(s.percentRead),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 10.dp, bottom = 11.dp).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            Text(
                "Novels by how far along they are",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
            )
            SegmentedBar(states.map { it.second }, colors)
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                states.forEachIndexed { i, (name, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(colors[i]))
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                        Text(
                            n.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One tall bar split by [counts], each part with its share written inside when there is room. */
@Composable
private fun SegmentedBar(counts: List<Int>, colors: List<Color>) {
    val total = counts.sum().coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)) {
        val full = maxWidth
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            counts.forEachIndexed { i, n ->
                if (n == 0) return@forEachIndexed
                val share = n.toFloat() / total
                Box(
                    Modifier.weight(share).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(colors[i]),
                    contentAlignment = Alignment.Center,
                ) {
                    val label = percent(share * 100)
                    if (full * share >= 44.dp) Text(
                        label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = if (colors[i].luminance() > 0.4f) Color(0xFF1B1B1F) else Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The headline figures, as tiles with a picture each. */
@Composable
private fun Tiles(s: ReadingStats, words: WordSample?, columns: Int) {
    val counting = words == null
    val tiles = listOf(
        Triple(Icons.Filled.LocalLibrary, "Novels", grouped(s.novels)),
        Triple(Icons.Filled.AutoStories, "Reading now", grouped(s.reading)),
        Triple(Icons.Filled.Extension, "Sources", grouped(s.progress.map { it.book.site }.distinct().size)),
        Triple(Icons.AutoMirrored.Filled.Notes, "Words read", if (counting) "…" else compact(s.wordsRead(words!!))),
        Triple(Icons.Filled.Schedule, "Reading time", if (counting) "…" else duration(s.minutesRead(words!!))),
        Triple(Icons.Filled.Download, "Downloaded", compact(s.downloaded.toLong())),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (icon, label, value) -> Tile(icon, label, value, Modifier.weight(1f)) }
            }
        }
        Text(
            if (counting) "Counting the words in stored chapters…"
            else "Words and time are estimates from the chapters stored on this device, at ${ReadingStats.WORDS_PER_MINUTE} words a minute.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = cardColor(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(
                value, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.SansSerif), fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A thin bar for how much of one novel is read. */
@Composable
private fun Meter(fraction: Float, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.primary.copy(alpha = 0.16f))) {
        if (fraction > 0f) Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.primary),
        )
    }
}

/** The novels started and not done, the one opened last first. */
@Composable
private fun ContinueReading(list: List<NovelProgress>, onOpenNovel: (Int) -> Unit) {
    Column {
        SectionTitle("Continue reading", "Opened most recently", Modifier.padding(start = 4.dp, bottom = 12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(list, key = { it.book.id }) { p ->
                Column(
                    Modifier.width(124.dp).clip(RoundedCornerShape(14.dp)).clickable { onOpenNovel(p.book.id) },
                ) {
                    BookCover(
                        p.book,
                        Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
                        MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        p.book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Meter(p.fraction, Modifier.fillMaxWidth().padding(top = 6.dp))
                    Text(
                        "${grouped(p.left)} left", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

/** Horizontal bars, longest first, each with its name and count beside it. */
@Composable
private fun RankedCard(title: String, subtitle: String, rows: List<Pair<String, Int>>, modifier: Modifier) {
    val top = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    StatCard(modifier) {
        Column {
            SectionTitle(title, subtitle, Modifier.padding(bottom = 14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { (name, n) ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text(grouped(n), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.fillMaxWidth().padding(top = 5.dp).height(10.dp)) {
                            Box(
                                Modifier.fillMaxWidth(n.toFloat() / top).fillMaxHeight()
                                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How the rated novels were rated: the average, and a column per star. */
@Composable
private fun RatingsCard(s: ReadingStats) {
    val top = s.ratings.max().coerceAtLeast(1)
    StatCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.padding(end = 28.dp)) {
                SectionTitle("Ratings", null)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        oneDecimal(s.averageRating),
                        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 40.sp,
                    )
                    Icon(Icons.Filled.Star, null, Modifier.padding(start = 6.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "average of ${s.rated} rated", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.weight(1f).height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom,
            ) {
                s.ratings.forEachIndexed { i, n ->
                    Column(Modifier.weight(1f, fill = false).widthIn(max = 56.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (n > 0) n.toString() else "", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Box(
                            Modifier.padding(top = 4.dp).fillMaxWidth().height(maxOf(2.dp, 76.dp * (n.toFloat() / top)))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (n > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        )
                        Text(
                            "${i + 1}★", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelRow(p: NovelProgress, onOpenNovel: (Int) -> Unit, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpenNovel(p.book.id) }.padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(p.book, Modifier.width(44.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)), MaterialTheme.typography.labelMedium)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                p.book.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(p.book.site, p.book.status).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Meter(p.fraction, Modifier.weight(1f))
                Text(
                    "${grouped(p.read)} / ${grouped(p.total)}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyStats(modifier: Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.BarChart, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("Nothing to count yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 18.dp))
        Text(
            "Add a novel to the library and read a little. This page fills in as you go.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).width(320.dp),
        )
    }
}

// ---- Numbers -------------------------------------------------------------

private fun oneDecimal(f: Float): String = String.format(Locale.getDefault(), "%.1f", f)

private fun grouped(n: Int): String = String.format(Locale.getDefault(), "%,d", n)

private fun percent(p: Float): String = when {
    p <= 0f -> "0%"
    p < 1f -> "<1%"
    p > 99f && p < 100f -> "99%"
    else -> "${p.toInt()}%"
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", n / 1_000_000f)
    n >= 10_000 -> "${n / 1_000}k"
    n >= 1_000 -> String.format(Locale.getDefault(), "%.1fk", n / 1_000f)
    else -> n.toString()
}

/** Minutes as the unit that reads best: "40 min", "12 h", "3.5 days". */
private fun duration(minutes: Long): String = when {
    minutes < 60 -> "$minutes min"
    minutes < 48 * 60 -> "${minutes / 60} h"
    minutes < 10 * 1440 -> String.format(Locale.getDefault(), "%.1f days", minutes / 1440f)
    else -> "${minutes / 1440} days"
}
