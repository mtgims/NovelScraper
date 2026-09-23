package com.novelscraper.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import kotlinx.coroutines.launch
import com.novelscraper.app.platform.rememberNarrationPermission
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.novelscraper.app.platform.PlatformBackHandler
import com.novelscraper.app.platform.SystemBarsVisible
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.data.Sentences
import com.novelscraper.app.net.Net
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
    // Leaving the chapter (back button / system back — the only ways out of the
    // reader) stops narration. Backgrounding or turning the screen off never calls
    // this, so playback keeps running there.
    val exit = {
        if (TtsController.state.value.active) TtsController.stop()
        onBack()
    }
    PlatformBackHandler(onBack = exit)
    val vm: ReaderViewModel = viewModel { ReaderViewModel() }
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

    // Immersive reading: the toolbars — and the Android status/nav bars — are hidden
    // so only the text shows. A single tap toggles them; scrolling hides them; they
    // start visible when a chapter opens (reset per chapter via the `pos` key). The
    // chapter content is always full-screen and the bars float on top, so toggling
    // them never changes the scroll geometry (keeping the scroll/TTS-centring math
    // stable).
    var chromeVisible by remember(pos) { mutableStateOf(true) }
    val toggleChrome = { chromeVisible = !chromeVisible }

    // The system status/nav bars follow `chromeVisible`, and come back when the
    // reader closes so the rest of the app isn't left full-screen.
    SystemBarsVisible(chromeVisible)

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

    val tts by TtsController.state.collectAsState()
    val ttsActiveHere = tts.active && tts.bookId == bookId && tts.position == pos
    // Narration rolls into the next chapter on its own: the reader follows it, so
    // the text on screen is what is being read.
    LaunchedEffect(tts.active, tts.bookId, tts.position) {
        if (tts.active && tts.bookId == bookId && tts.position != pos) pos = tts.position
    }

    // Keyboard (desktop, or a keyboard on a tablet): arrows change chapter, Space /
    // Page Down and Shift+Space / Page Up turn the page, P plays or pauses
    // narration, Ctrl +/- change the font size. Esc (back) is handled above.
    val keyScope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val startListen = rememberNarrationPermission {
        TtsController.play(bookId, pos, meta?.book?.title ?: "", currentStartIndex())
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun page(down: Boolean) {
        val step = (scroll.viewportSize * 0.9f).coerceAtLeast(1f)
        keyScope.launch { scroll.animateScrollBy(if (down) step else -step) }
    }
    val onKey: (KeyEvent) -> Boolean = { e ->
        if (e.type != KeyEventType.KeyDown) false
        else when {
            e.isCtrlPressed && (e.key == Key.Equals || e.key == Key.Plus || e.key == Key.NumPadAdd) -> {
                ReaderPrefs.increaseFont(); true
            }
            e.isCtrlPressed && (e.key == Key.Minus || e.key == Key.NumPadSubtract) -> {
                ReaderPrefs.decreaseFont(); true
            }
            e.isCtrlPressed || e.isAltPressed || e.isMetaPressed -> false
            e.key == Key.DirectionRight && data?.chapter?.has_next == true -> { pos += 1; true }
            e.key == Key.DirectionLeft && data?.chapter?.has_prev == true -> { pos -= 1; true }
            e.key == Key.PageDown || (e.key == Key.Spacebar && !e.isShiftPressed) -> { page(down = true); true }
            e.key == Key.PageUp || (e.key == Key.Spacebar && e.isShiftPressed) -> { page(down = false); true }
            e.key == Key.P -> {
                if (ttsActiveHere) TtsController.toggle() else if (data != null) startListen()
                true
            }
            else -> false
        }
    }

    // Keep pulling at the end of a chapter (or at the top) and the reader moves
    // to the next one, so a novel reads through without reaching for a button.
    val turnDistance = with(LocalDensity.current) { 140.dp.toPx() }
    val hasNext = data?.chapter?.has_next == true
    val hasPrev = data?.chapter?.has_prev == true
    val turnPages = remember(pos, hasNext, hasPrev, turnDistance) {
        object : NestedScrollConnection {
            private var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                // Nothing left to scroll: what the list couldn't use is the pull.
                if (available.y == 0f) { pulled = 0f; return Offset.Zero }
                pulled = if (pulled != 0f && (pulled < 0) != (available.y < 0)) available.y else pulled + available.y
                if (pulled < -turnDistance && hasNext) { pulled = 0f; pos += 1 }
                else if (pulled > turnDistance && hasPrev) { pulled = 0f; pos -= 1 }
                return Offset.Zero
            }
        }
    }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent(onKey).focusRequester(focus).focusable()
            .nestedScroll(turnPages),
    ) {
        // Full-screen chapter content — its geometry is independent of the bars.
        val s = state
        when {
            s is ReaderState.Error ->
                Text(s.message, Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error)
            data == null ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            else ->
                ChapterBody(
                    bookId, pos, data, fontScale, vm, scroll, plain, ranges,
                    onToggleChrome = toggleChrome,
                    onScrolled = { chromeVisible = false },
                )
        }

        // Top toolbar (title, back, chapters, font size) — slides down over the text.
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            TopAppBar(
                title = {
                    Text(data?.chapter?.title ?: meta?.chapters?.firstOrNull { it.position == pos }?.title ?: "",
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
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
        }

        // Bottom cluster: the Listen pill sitting on top of the prev/next bar. They
        // rise from the bottom edge together and leave together, as one piece.
        //
        // The pill is also allowed to stay on its own while narration is running
        // and the bars are hidden, so playback stays controllable. In that state
        // the bar folds away beneath it and the pill settles down to the edge.
        //
        // `barShown` is frozen while the cluster is hidden or leaving. Otherwise,
        // hiding everything would also fold the bar away mid-exit and the pill
        // would drop faster than the bar, instead of the two sliding out as one.
        val clusterVisible = chromeVisible || ttsActiveHere
        val barShownState = remember { mutableStateOf(chromeVisible) }
        if (clusterVisible) barShownState.value = chromeVisible
        val barShown = barShownState.value

        AnimatedVisibility(
            visible = clusterVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ReaderTtsBar(
                    bookId = bookId,
                    position = pos,
                    bookTitle = meta?.book?.title ?: "",
                    startIndex = { currentStartIndex() },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp)
                        // Alone, the pill sits over the (hidden) navigation bar area.
                        .then(if (barShown) Modifier else Modifier.navigationBarsPadding()),
                )
                AnimatedVisibility(
                    visible = barShown,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
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
                    }
                }
            }
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

// A rendered chapter block: a run of text paragraphs, or an illustration.
private sealed interface RBlock
private class TextBlock(
    val text: String,             // slice of `plain` covering this block's sentences
    val firstIndex: Int,          // global sentence index of the first sentence here
    val lastIndex: Int,           // global sentence index of the last
    val locals: List<IntRange>,   // per-sentence ranges within `text` (firstIndex..lastIndex)
) : RBlock {
    fun localRangeOf(global: Int): IntRange? = locals.getOrNull(global - firstIndex)
    fun globalIndexAt(off: Int): Int {
        val i = locals.indexOfFirst { off >= it.first && off <= it.last }
        return if (i >= 0) firstIndex + i else -1
    }
}
private class ImageBlock(val url: String) : RBlock

// Split the flattened chapter into text blocks + image blocks. Each image is its own
// 1-char (OBJ) "sentence" in `ranges` (isolated by Sentences.plain), so the global
// sentence indexing the reader shares with TtsService is preserved: text blocks keep
// their exact global indices, images map in order to imageUrls (nulls dropped).
private fun buildBlocks(plain: String, ranges: List<IntRange>, imageUrls: List<String?>): List<RBlock> {
    fun isImage(r: IntRange) = r.first == r.last && r.first < plain.length && plain[r.first] == Sentences.OBJ
    val out = ArrayList<RBlock>()
    var imgIdx = 0
    var i = 0
    while (i < ranges.size) {
        if (isImage(ranges[i])) {
            imageUrls.getOrNull(imgIdx)?.let { out.add(ImageBlock(it)) }
            imgIdx++
            i++
        } else {
            // One block per paragraph, not one per run of text. A paragraph break
            // survives the flattening as a single newline, which on its own reads
            // as a wall of text: as separate blocks they can be given air between
            // them. Sentence indices are untouched, because the split falls in the
            // gap between two sentences, where no index lives.
            val start = i
            i++
            while (i < ranges.size && !isImage(ranges[i]) &&
                !plain.substring(ranges[i - 1].last + 1, ranges[i].first).contains('\n')
            ) i++
            val base = ranges[start].first
            val end = ranges[i - 1].last + 1
            out.add(TextBlock(
                text = plain.substring(base, end),
                firstIndex = start,
                lastIndex = i - 1,
                locals = (start until i).map { (ranges[it].first - base)..(ranges[it].last - base) },
            ))
        }
    }
    return out
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
    onToggleChrome: () -> Unit,
    onScrolled: () -> Unit,
) {
    val ctx = LocalPlatformContext.current
    val tts by TtsController.state.collectAsState()
    val activeHere = tts.active && tts.bookId == bookId && tts.position == pos
    val curIdx = if (activeHere) tts.sentenceIndex else -1

    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

    // Chapter illustrations. HtmlCompat leaves one OBJ char per <img> in `plain`
    // (isolated on its own line), so each image is its own global "sentence" and the
    // sentence indexing the reader shares with TtsService is preserved. We render the
    // chapter as blocks — text paragraphs as Text, images as real composables. (Inline
    // content can't lay out a full-width image without overlapping the surrounding
    // text, so a block Column is used instead.)
    val imageUrls = remember(data.chapter.content) {
        Sentences.imageSrcs(data.chapter.content).map { Net.contentImageUrl(it) }
    }
    val blocks = remember(plain, ranges, imageUrls) { buildBlocks(plain, ranges, imageUrls) }

    // Per-text-block text layout + absolute (root) Y, keyed by the block's first
    // global sentence index — used to map taps and to centre the spoken sentence.
    val layouts = remember(plain) { mutableStateMapOf<Int, TextLayoutResult>() }
    val blockYs = remember(plain) { mutableStateMapOf<Int, Float>() }

    var viewportRootY by remember { mutableStateOf(0f) }
    var viewportH by remember { mutableStateOf(0) }

    // Restore the saved in-chapter scroll once content has laid out. `savedFrac` is
    // captured before the persist effect can overwrite it; `restored` gates writes
    // so we don't clobber the saved value with 0 during the pre-layout window.
    // The resume point's sentence places it the same on any device or screen;
    // otherwise this device's own scroll for the chapter.
    val savedFrac = remember(bookId, pos) {
        data.anchor?.let { i -> ranges.getOrNull(i)?.first }?.takeIf { plain.isNotEmpty() }
            ?.let { it.toFloat() / plain.length }
            ?: ReaderPrefs.getScroll(data.scrollKey, pos)
    }
    var restored by remember(bookId, pos) { mutableStateOf(false) }
    LaunchedEffect(bookId, pos) {
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        runCatching { scroll.scrollTo((savedFrac * scroll.maxValue).roundToInt()) }
        restored = true
    }
    LaunchedEffect(bookId, pos) {
        snapshotFlow { if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f }
            .collect { f -> if (restored) ReaderPrefs.setScroll(data.scrollKey, pos, f) }
    }
    // Hide the bars once the reader actually starts scrolling (after the initial
    // restore, so opening a chapter doesn't immediately hide them). TTS auto-centring
    // also scrolls, which keeps the view immersive while narrating.
    LaunchedEffect(bookId, pos) {
        snapshotFlow { scroll.isScrollInProgress }
            .collect { inProgress -> if (inProgress && restored) onScrolled() }
    }
    val fracNow = rememberUpdatedState(
        if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
    )
    DisposableEffect(bookId, pos) {
        onDispose {
            if (restored) {
                // The sentence at that scroll, the same mapping narration starts from.
                val off = (fracNow.value * plain.length).roundToInt()
                val sentence = ranges.indexOfFirst { off <= it.last }.takeIf { it >= 0 }
                vm.saveScroll(bookId, pos, fracNow.value, sentence)
            }
        }
    }

    // Keep the spoken sentence centred in the viewport (so the floating Listen pill
    // never covers it). Uses the spoken sentence's block layout + absolute root
    // coordinates, then scrolls by the delta needed to bring that line to centre.
    LaunchedEffect(curIdx) {
        if (curIdx < 0) return@LaunchedEffect
        if (scroll.maxValue <= 0) snapshotFlow { scroll.maxValue }.first { it > 0 }
        if (viewportH <= 0) return@LaunchedEffect
        val blk = blocks.firstOrNull {
            it is TextBlock && curIdx >= it.firstIndex && curIdx <= it.lastIndex
        } as? TextBlock ?: return@LaunchedEffect
        val l = layouts[blk.firstIndex] ?: return@LaunchedEffect
        val rootY = blockYs[blk.firstIndex] ?: return@LaunchedEffect
        val local = blk.localRangeOf(curIdx) ?: return@LaunchedEffect
        val line = l.getLineForOffset(local.first.coerceIn(0, (blk.text.length - 1).coerceAtLeast(0)))
        val lineCenter = (l.getLineTop(line) + l.getLineBottom(line)) / 2f
        val sentenceAbsY = rootY + lineCenter                     // on-screen Y of the line
        val desiredAbsY = viewportRootY + viewportH / 2f          // viewport centre
        val target = (scroll.value + (sentenceAbsY - desiredAbsY)).roundToInt()
            .coerceIn(0, scroll.maxValue)
        runCatching { scroll.animateScrollTo(target) }
    }

    fun onSentenceTap(idx: Int) {
        val s = TtsController.state.value
        if (s.active && s.bookId == bookId && s.position == pos) TtsController.seek(idx)
        else TtsController.play(bookId, pos, "", idx)
    }

    Column(
        Modifier.fillMaxSize()
            .onGloballyPositioned { viewportRootY = it.localToRoot(Offset.Zero).y; viewportH = it.size.height }
            .verticalScroll(scroll)
            // A tap on empty space (margins, gaps, title, images) toggles the bars;
            // taps on a text paragraph are handled by the paragraph's own detector.
            .pointerInput(Unit) { detectTapGestures { onToggleChrome() } }
            .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val endHint = if (data.chapter.has_next) "Keep scrolling for the next chapter"
                      else "That's the last chapter"
        val measure = Modifier.widthIn(max = 620.dp).fillMaxWidth()
        Text(
            data.chapter.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = measure.padding(bottom = 20.dp),
        )
        blocks.forEach { block ->
            when (block) {
                is TextBlock -> {
                    val annotated = buildAnnotatedString {
                        append(block.text)
                        if (curIdx in block.firstIndex..block.lastIndex) {
                            block.localRangeOf(curIdx)?.let {
                                addStyle(SpanStyle(background = highlight), it.first, it.last + 1)
                            }
                        }
                    }
                    Text(
                        annotated,
                        fontFamily = Serif,
                        fontSize = (19 * fontScale).sp,
                        lineHeight = (31 * fontScale).sp,
                        // Paragraphs are told apart by the space after them and the
                        // indent on the line that starts one, which is how a book
                        // does it.
                        style = LocalTextStyle.current.copy(
                            textIndent = TextIndent(firstLine = (14 * fontScale).sp),
                        ),
                        onTextLayout = { layouts[block.firstIndex] = it },
                        modifier = measure
                            .padding(bottom = (10 * fontScale).dp)
                            .onGloballyPositioned { blockYs[block.firstIndex] = it.localToRoot(Offset.Zero).y }
                            .pointerInput(bookId, pos, block.firstIndex) {
                                // Single tap toggles the bars; long-press on a sentence
                                // starts (or seeks) narration from it.
                                detectTapGestures(
                                    onTap = { onToggleChrome() },
                                    onLongPress = { offset ->
                                        val lr = layouts[block.firstIndex] ?: return@detectTapGestures
                                        val gi = block.globalIndexAt(lr.getOffsetForPosition(offset))
                                        if (gi >= 0) onSentenceTap(gi)
                                    },
                                )
                            },
                    )
                }
                is ImageBlock -> AsyncImage(
                    model = ImageRequest.Builder(ctx).data(block.url).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = measure.padding(vertical = 10.dp),
                )
            }
        }
        Text(
            endHint,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 28.dp),
        )
        Box(Modifier.padding(bottom = 110.dp)) // clear the floating Listen pill
    }
}
