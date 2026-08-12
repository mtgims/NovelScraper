package com.novelscraper.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.ui.ReaderState
import com.novelscraper.app.ui.ReaderViewModel
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: Int, position: Int, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val vm: ReaderViewModel = viewModel()
    var pos by rememberSaveable { mutableIntStateOf(position) }
    LaunchedEffect(pos) { vm.load(bookId, pos) }

    val state by vm.state.collectAsState()
    val fontScale by ReaderPrefs.fontScale.collectAsState()
    val data = state as? ReaderState.Data

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(data?.chapter?.title ?: "Loading…", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { ReaderPrefs.decreaseFont() }) { Text("A-") }
                    TextButton(onClick = { ReaderPrefs.increaseFont() }) { Text("A+") }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(enabled = data?.chapter?.has_prev == true, onClick = { pos -= 1 }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = null)
                    Text("Prev")
                }
                Text("Chapter $pos", style = MaterialTheme.typography.labelMedium)
                TextButton(enabled = data?.chapter?.has_next == true, onClick = { pos += 1 }) {
                    Text("Next")
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is ReaderState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ReaderState.Error ->
                    Text(s.message, Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.error)
                is ReaderState.Data ->
                    ChapterBody(bookId, pos, s, fontScale, vm)
            }
        }
    }
}

@Composable
private fun ChapterBody(
    bookId: Int,
    pos: Int,
    data: ReaderState.Data,
    fontScale: Float,
    vm: ReaderViewModel,
) {
    val scroll = rememberScrollState()
    val body = remember(data.chapter.content) { AnnotatedString.fromHtml(data.chapter.content) }

    // Restore saved in-chapter scroll once the content has laid out.
    LaunchedEffect(bookId, pos) {
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        val f = ReaderPrefs.getScroll(bookId, pos)
        if (f > 0f) runCatching { scroll.scrollTo((f * scroll.maxValue).roundToInt()) }
    }
    // Persist scroll fraction locally as the reader scrolls.
    LaunchedEffect(bookId, pos) {
        snapshotFlow { if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f }
            .collect { f -> ReaderPrefs.setScroll(bookId, pos, f) }
    }
    // On leaving this chapter, sync the scroll fraction to the server.
    val fracNow = rememberUpdatedState(
        if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
    )
    DisposableEffect(bookId, pos) {
        onDispose { vm.saveScroll(bookId, pos, fracNow.value) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            data.chapter.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text(
            body,
            fontSize = (18 * fontScale).sp,
            lineHeight = (28 * fontScale).sp,
        )
        Box(Modifier.padding(bottom = 32.dp))
    }
}
