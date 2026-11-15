package com.novelscraper.app.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.platform.PlatformBackHandler
import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.ui.components.ColumnScrollbar
import com.novelscraper.app.ui.components.ContentWidth
import com.novelscraper.app.ui.components.UpdateSection
import com.novelscraper.app.ui.theme.Kicker
import com.novelscraper.app.ui.theme.Serif
import com.novelscraper.app.ui.theme.THEMES
import com.novelscraper.app.ui.theme.ThemeController

/**
 * Settings: a short list of areas, each with how it stands now, and a page for
 * each. Everything on one long page made a phone scroll past voice lists to
 * reach sync, with every control styled its own way.
 *
 * A wide window shows the list and the open page side by side.
 */
@Composable
fun SettingsScreen() {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= TWO_PANES) {
            val page = open?.let { SettingsPage.valueOf(it) } ?: SettingsPage.APPEARANCE
            Row(Modifier.fillMaxSize()) {
                Scrolling(Modifier.width(380.dp)) {
                    Overview(selected = page, onOpen = { open = it.name })
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outline)
                Box(Modifier.weight(1f)) {
                    key(page) {
                        Scrolling {
                            Text(page.title, style = MaterialTheme.typography.headlineMedium)
                            page.content()
                        }
                    }
                }
            }
        } else {
            PlatformBackHandler(enabled = open != null) { open = null }
            AnimatedContent(
                targetState = open,
                transitionSpec = {
                    // A window fades between pages, as the rest of the app does there.
                    if (isDesktop) return@AnimatedContent fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                    val forward = targetState != null
                    val dir = if (forward) SlideDirection.Left else SlideDirection.Right
                    (slideIntoContainer(dir, tween(240)) + fadeIn(tween(240))) togetherWith
                        (slideOutOfContainer(dir, tween(240)) + fadeOut(tween(160)))
                },
                label = "settings page",
            ) { name ->
                if (name == null) Scrolling { Overview(selected = null, onOpen = { open = it.name }) }
                else PageWithBack(SettingsPage.valueOf(name), onBack = { open = null })
            }
        }
    }
}

/** Settings only one platform has, filled in by that platform when it starts
 *  (the desktop's GPU narration). */
object SettingsHooks {
    var narration: (@Composable () -> Unit)? = null
}

internal enum class SettingsPage(val title: String, val icon: ImageVector, val content: @Composable () -> Unit) {
    APPEARANCE("Appearance", Icons.Outlined.Palette, { AppearancePage() }),
    NARRATION("Narration", Icons.Outlined.RecordVoiceOver, { NarrationPage() }),
    SYNC("Sync", Icons.Outlined.Sync, { SyncPage() }),
}

/** Wide enough for the list and a page beside it, each at a comfortable width. */
private val TWO_PANES = 840.dp

@Composable
private fun Overview(selected: SettingsPage?, onOpen: (SettingsPage) -> Unit) {
    Text("Settings", style = MaterialTheme.typography.headlineMedium)
    SettingsGroup {
        SettingsPage.entries.forEachIndexed { i, p ->
            if (i > 0) GroupDivider(inset = ROW_TEXT_INSET)
            val (summary, trouble) = when (p) {
                SettingsPage.APPEARANCE -> appearanceSummary() to false
                SettingsPage.NARRATION -> narrationSummary() to false
                SettingsPage.SYNC -> syncSummary()
            }
            SettingsRow(
                title = p.title,
                summary = summary,
                summaryColor = if (trouble) MaterialTheme.colorScheme.error else Color.Unspecified,
                icon = p.icon,
                selected = p == selected,
                onClick = { onOpen(p) },
                trailing = if (selected == null) {
                    { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else null,
            )
        }
    }
    SettingsGroup(title = "This app") {
        GroupBody { UpdateSection() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageWithBack(page: SettingsPage, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(page.title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        Scrolling(Modifier.weight(1f), top = 0.dp, statusBar = false) { page.content() }
    }
}

/** A column of settings that scrolls, kept to a readable width, clear of the phone's floating tab bar. */
@Composable
private fun Scrolling(
    modifier: Modifier = Modifier,
    top: Dp = 20.dp,
    statusBar: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxHeight()) {
        ContentWidth(Modifier.then(if (statusBar) Modifier.statusBarsPadding() else Modifier).verticalScroll(scroll), max = 720.dp) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = top, bottom = if (isDesktop) 40.dp else 120.dp),
                content = content,
            )
        }
        ColumnScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

// --- Appearance ----------------------------------------------------------------------

@Composable
private fun appearanceSummary(): String {
    val theme by ThemeController.theme.collectAsState()
    val fontScale by ReaderPrefs.fontScale.collectAsState()
    val label = THEMES.firstOrNull { it.name == theme }?.label ?: theme
    return "$label theme · text ${(fontScale * 100).toInt()}%"
}

@Composable
private fun AppearancePage() {
    val theme by ThemeController.theme.collectAsState()
    val fontScale by ReaderPrefs.fontScale.collectAsState()

    SettingsGroup(title = "Theme") {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            THEMES.forEach { t ->
                val selected = t.name == theme
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .clickable { ThemeController.setTheme(t.name) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(t.swatchBg)
                            .border(
                                BorderStroke(if (selected) 3.dp else 1.dp,
                                    if (selected) t.accent else MaterialTheme.colorScheme.outline),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Filled.Check, "Selected", tint = t.accent, modifier = Modifier.size(22.dp))
                    }
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    SettingsGroup(title = "Reading") {
        SettingsRow(
            title = "Text size",
            summary = "${(fontScale * 100).toInt()}% in the reader",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = { ReaderPrefs.decreaseFont() }) {
                        Icon(Icons.Filled.Remove, "Smaller text")
                    }
                    FilledTonalIconButton(onClick = { ReaderPrefs.increaseFont() }) {
                        Icon(Icons.Filled.Add, "Larger text")
                    }
                }
            },
        )
        GroupDivider()
        // The reader's own paragraph style, so the size is seen rather than imagined.
        Text(
            "The rain had not stopped for three days, and the inn at the edge of the pass was full of " +
                "travellers who had nowhere else to go.",
            fontFamily = Serif,
            fontSize = (19 * fontScale).sp,
            lineHeight = (31 * fontScale).sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}

// --- the parts every page is built from ----------------------------------------------

/** Where a row's text starts when it has an icon, for dividers that line up with it. */
internal val ROW_TEXT_INSET = 68.dp

/** A card of related settings, with an optional label above it. */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = 22.dp)) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = cardColor(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

/** A card stands off the page in every theme: white on Light's grey, a step lighter on the dark ones. */
@Composable
internal fun cardColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) MaterialTheme.colorScheme.surfaceContainerLowest
    else MaterialTheme.colorScheme.surfaceContainerHigh

/** Free-form content inside a group, padded like a row. */
@Composable
internal fun GroupBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(16.dp), content = content)
}

@Composable
internal fun GroupDivider(inset: Dp = 0.dp) {
    HorizontalDivider(Modifier.padding(start = inset), color = MaterialTheme.colorScheme.outline)
}

/** A small heading inside a group, for a list split in parts (voices by accent). */
@Composable
internal fun GroupSubhead(text: String) {
    Text(
        text.uppercase(),
        style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
    )
}

/**
 * One row of a group: an icon or a control in front, what it is and how it
 * stands, and what it does at the end. Tapping anywhere on it does [onClick].
 */
@Composable
internal fun SettingsRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    titleColor: Color = Color.Unspecified,
    summaryColor: Color = Color.Unspecified,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (titleColor != Color.Unspecified) titleColor else MaterialTheme.colorScheme.primary
        if (icon != null) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
        } else if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    titleColor != Color.Unspecified -> titleColor
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (summaryColor != Color.Unspecified) summaryColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
