package com.novelscraper.app.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.novelscraper.app.ui.theme.NovelScraperTheme
import com.novelscraper.app.ui.theme.ThemeController
import kotlin.test.fail

/**
 * Draws a piece of the app at phone widths, with the system font at its
 * default size and enlarged, and finds text that doesn't fit: cut off because
 * its box is shorter than its lines (the ratings chart's "5★" under the
 * tallest bar, 2026-11-18, seen only on a phone), or running off the side of
 * the screen. Checked through the semantics tree, so no pictures to compare.
 *
 * Lists draw only what is on screen, so [height] is set tall enough for the
 * whole of what is checked.
 */
object LayoutCheck {
    /** A small phone and a common one, in dp. */
    val widths = listOf(360, 412)
    /** Android's default and its "large" font size. */
    val fontScales = listOf(1.0f, 1.3f)

    /** Text measured so far, to see that a check looked at something. */
    var checked = 0
        private set

    @OptIn(ExperimentalTestApi::class)
    fun problems(width: Int, fontScale: Float, height: Int = 2400, content: @Composable () -> Unit): List<String> {
        ThemeController.init()
        val found = mutableListOf<String>()
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    NovelScraperTheme {
                        Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) { content() } }
                    }
                }
            }
            waitForIdle()
            val nodes = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                .fetchSemanticsNodes()
            if (nodes.isEmpty()) found += "nothing was drawn to check"
            checked += nodes.size
            for (node in nodes) {
                val text = node.text()
                val layout = node.layout() ?: continue
                // Taller than its box: lines drawn outside it, or clipped. Text
                // shortened with an ellipsis is laid out to fit and isn't.
                val lines = layout.multiParagraph.height
                if (lines > node.size.height + 0.5f) found += "cut off (box ${node.size.height}px for ${lines.toInt()}px of text): \"$text\""
                // Past the screen's edge, unless it is in a row that scrolls sideways.
                val right = node.positionInRoot.x + node.size.width
                if (right > width + 0.5f && !node.scrollsSideways()) found += "runs off the screen (${right.toInt()} of $width): \"$text\""
            }
        }
        return found.map { "${width}dp, font ${fontScale}x: $it" }
    }

    /** Every phone size and font size; fails listing every problem. */
    fun assertFits(what: String, height: Int = 2400, content: @Composable () -> Unit) {
        val all = widths.flatMap { w -> fontScales.flatMap { fs -> problems(w, fs, height, content) } }.distinct()
        if (all.isNotEmpty()) fail("$what doesn't fit on a phone:\n" + all.joinToString("\n"))
    }

    private fun SemanticsNode.scrollsSideways(): Boolean {
        var n: SemanticsNode? = parent
        while (n != null) {
            if (n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null) return true
            n = n.parent
        }
        return false
    }

    private fun SemanticsNode.text(): String =
        config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.take(60) ?: "?"

    private fun SemanticsNode.layout(): TextLayoutResult? {
        val out = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.firstOrNull()
    }
}
