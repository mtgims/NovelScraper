package com.novelscraper.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.data.Sentences
import com.novelscraper.app.tts.TtsController
import com.novelscraper.app.ui.components.ChaptersSheet
import com.novelscraper.app.ui.components.ReaderTtsBar
import com.novelscraper.app.ui.theme.Serif
import com.novelscraper.app.ui.ReaderState
import com.novelscraper.app.ui.ReaderViewModel
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: Int, position: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // Leaving the chapter (back button / system back — the only ways out of the
    // reader) stops narration. Backgrounding or turning the screen off never calls
    // this, so playback keeps running there.
    val exit = {
        if (TtsController.state.value.active) TtsController.stop(ctx)
        onBack()
    }
    BackHandler(onBack = exit)
    val vm: ReaderViewModel = viewModel()
    var pos by rememberSaveable { mutableIntStateOf(position) }
    LaunchedEffect(pos) { vm.load(bookId, pos) }
    LaunchedEffect(bookId) { vm.ensureMeta(bookId) }

    val state by vm.state.collectAsState()
    val meta by vm.meta.collectAsState()
    val fontScale by ReaderPrefs.fontScale.collectAsState()
    // Only treat the loaded chapter as current when it matches `pos` — otherwise a
    // stale (previous) chapter renders for a frame during navigation.
    val data = (state as? ReaderState.Data)?.takeIf { it.chapter.position == pos }

    var showChapters by remember { mutableStateOf(false) }

    // Scroll + sentence model hoisted here so the Listen pill can start narration
    // from the sentence currently on screen. Fresh scroll state per chapter.
    val scroll = remember(bookId, pos) { ScrollState(0) }
    val content = data?.chapter?.content
    val plain = remember(content) { content?.let { Sentences.plain(it) } ?: "" }
    val ranges = remember(plain) { Sentences.ranges(plain) }
    fun currentStartIndex(): Int {
        if (ranges.isEmpty() || scroll.maxValue <= 0) return 0
        val frac = scroll.value.toFloat() / scroll.maxValue
        val off = (frac * plain.length).roundToInt()
        val i = ranges.indexOfFirst { off <= it.last }
        return if (i >= 0) i else 0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(data?.chapter?.title ?: "Loading…", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = exit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showChapters = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Chapters")
                    }
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
            val s = state
            when {
                s is ReaderState.Error ->
                    Text(s.message, Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.error)
                data == null ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                else ->
                    ChapterBody(bookId, pos, data, fontScale, vm, scroll, plain, ranges)
            }
            ReaderTtsBar(
                bookId = bookId,
                position = pos,
                bookTitle = meta?.book?.title ?: "",
                startIndex = { currentStartIndex() },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    val m = meta
    if (showChapters && m != null) {
        ChaptersSheet(
            title = m.book.title,
            chapters = m.chapters,
            readPositions = m.readPositions,
            currentPos = pos,
            onJump = { p -> pos = p; showChapters = false },
            onToggleRead = { p, read -> vm.setChapterRead(bookId, p, read) },
            onDismiss = { showChapters = false },
        )
    }
}

@Composable
private fun ChapterBody(
    bookId: Int,
    pos: Int,
    data: ReaderState.Data,
    fontScale: Float,
    vm: ReaderViewModel,
    scroll: ScrollState,
    plain: String,
    ranges: List<IntRange>,
) {
    val ctx = LocalContext.current
    val tts by TtsController.state.collectAsState()
    val activeHere = tts.active && tts.bookId == bookId && tts.position == pos
    val curIdx = if (activeHere) tts.sentenceIndex else -1

    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val annotated = remember(plain, curIdx, highlight) {
        buildAnnotatedString {
            append(plain)
            ranges.getOrNull(curIdx)?.let { addStyle(SpanStyle(background = highlight), it.first, it.last + 1) }
        }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val layoutRef = rememberUpdatedState(layout)
    val rangesRef = rememberUpdatedState(ranges)

    // Absolute (root) positions used to centre the spoken sentence in the viewport.
    var textRootY by remember { mutableStateOf(0f) }
    var viewportRootY by remember { mutableStateOf(0f) }
    var viewportH by remember { mutableStateOf(0) }

    // Restore the saved in-chapter scroll once content has laid out. `savedFrac` is
    // captured before the persist effect can overwrite it; `restored` gates writes
    // so we don't clobber the saved value with 0 during the pre-layout window.
    val savedFrac = remember(bookId, pos) { ReaderPrefs.getScroll(bookId, pos) }
    var restored by remember(bookId, pos) { mutableStateOf(false) }
    LaunchedEffect(bookId, pos) {
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        runCatching { scroll.scrollTo((savedFrac * scroll.maxValue).roundToInt()) }
        restored = true
    }
    LaunchedEffect(bookId, pos) {
        snapshotFlow { if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f }
            .collect { f -> if (restored) ReaderPrefs.setScroll(bookId, pos, f) }
    }
    val fracNow = rememberUpdatedState(
        if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
    )
    DisposableEffect(bookId, pos) {
        onDispose { if (restored) vm.saveScroll(bookId, pos, fracNow.value) }
    }

    // Keep the spoken sentence centred in the viewport (so the floating Listen pill
    // never covers it). Uses the text layout's real line position + absolute root
    // coordinates, then scrolls by the delta needed to bring that line to centre.
    LaunchedEffect(curIdx) {
        if (curIdx < 0) return@LaunchedEffect
        val r = ranges.getOrNull(curIdx) ?: return@LaunchedEffect
        if (scroll.maxValue <= 0) snapshotFlow { scroll.maxValue }.first { it > 0 }
        val l = layoutRef.value ?: return@LaunchedEffect
        if (viewportH <= 0) return@LaunchedEffect
        val line = l.getLineForOffset(r.first.coerceIn(0, (plain.length - 1).coerceAtLeast(0)))
        val lineCenter = (l.getLineTop(line) + l.getLineBottom(line)) / 2f
        val sentenceAbsY = textRootY + lineCenter                 // on-screen Y of the line
        val desiredAbsY = viewportRootY + viewportH / 2f          // viewport centre
        val target = (scroll.value + (sentenceAbsY - desiredAbsY)).roundToInt()
            .coerceIn(0, scroll.maxValue)
        runCatching { scroll.animateScrollTo(target) }
    }

    fun onSentenceTap(idx: Int) {
        val s = TtsController.state.value
        if (s.active && s.bookId == bookId && s.position == pos) TtsController.seek(ctx, idx)
        else TtsController.play(ctx, bookId, pos, "", idx)
    }

    Column(
        Modifier.fillMaxSize()
            .onGloballyPositioned { viewportRootY = it.localToRoot(Offset.Zero).y; viewportH = it.size.height }
            .verticalScroll(scroll)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val measure = Modifier.fillMaxWidth().widthIn(max = 620.dp)
        Text(
            data.chapter.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = measure.padding(bottom = 20.dp),
        )
        Text(
            annotated,
            fontFamily = Serif,
            fontSize = (19 * fontScale).sp,
            lineHeight = (31 * fontScale).sp,
            onTextLayout = { layout = it },
            modifier = measure
                .onGloballyPositioned { textRootY = it.localToRoot(Offset.Zero).y }
                .pointerInput(bookId, pos) {
                    detectTapGestures { offset ->
                        val lr = layoutRef.value ?: return@detectTapGestures
                        val off = lr.getOffsetForPosition(offset)
                        val idx = rangesRef.value.indexOfFirst { off >= it.first && off <= it.last }
                        if (idx >= 0) onSentenceTap(idx)
                    }
                },
        )
        Box(Modifier.padding(bottom = 110.dp)) // clear the floating Listen pill
    }
}
