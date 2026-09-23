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
import com.novelscraper.app.ui.components.ContentWidth
import com.novelscraper.app.platform.isDesktop
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelscraper.app.data.JobRead
import com.novelscraper.app.ui.ProgressViewModel
import com.novelscraper.app.ui.theme.Mono
import kotlinx.coroutines.delay

@Composable
fun ProgressScreen() {
    val vm: ProgressViewModel = viewModel { ProgressViewModel() }
    val jobs by vm.jobs.collectAsState()
    val loaded by vm.loaded.collectAsState()

    // Poll while this screen is visible so live scrapes update. The first tick is
    // deferred so the network result + list recompose don't land mid-transition.
    LaunchedEffect(Unit) {
        delay(300)
        while (true) { vm.refresh(); delay(1500) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Progress", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (jobs.any { it.status in TERMINAL }) {
                TextButton(onClick = vm::clearFinished) { Text("Clear finished") }
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (loaded && jobs.isEmpty()) {
                Text("No scrapes yet.", Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge)
            } else {
                ContentWidth {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp,
                            bottom = if (isDesktop) 24.dp else 110.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(jobs, key = { it.id }) { job -> JobCard(job, vm) }
                    }
                }
            }
        }
    }
}

private val TERMINAL = setOf("completed", "failed", "cancelled")

@Composable
private fun JobCard(job: JobRead, vm: ProgressViewModel) {
    val active = job.status == "queued" || job.status == "running"
    val frac = if (job.total_chapters > 0) job.fetched_chapters.toFloat() / job.total_chapters else 0f
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(job.book_slug, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${job.site} · ${job.status}${if (job.phase.isNotBlank()) " · ${job.phase}" else ""}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))

            if (active || job.total_chapters > 0) {
                LinearProgressIndicator(
                    progress = { frac.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Text(
                    "${job.fetched_chapters} / ${job.total_chapters}" +
                        if (job.skipped_chapters > 0) "  (${job.skipped_chapters} skipped)" else "",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            job.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (active) {
                    TextButton(onClick = { vm.cancel(job.id) }) { Text("Cancel") }
                } else {
                    TextButton(onClick = { vm.delete(job.id) }) { Text("Remove") }
                }
            }
        }
    }
}
