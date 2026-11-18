package com.novelscraper.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.novelscraper.app.extensions.compareVersions
import com.novelscraper.app.library.Library
import com.novelscraper.app.platform.PlatformBackHandler
import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.platform.settingsStore
import com.novelscraper.app.ui.components.ContentWidth
import com.novelscraper.app.update.AppUpdates
import com.novelscraper.app.update.Changelog

/** Every release's notes, newest first, as cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    PlatformBackHandler(onBack = onBack)
    val releases = remember { Changelog.releases }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("What's new") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        if (releases.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text("This build carries no changelog.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        ContentWidth {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (isDesktop) 24.dp else 48.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(releases, key = { it.version }) { r -> ReleaseCard(r) }
            }
        }
    }
}

/**
 * Once after an update, the notes of the versions that came with it. A first
 * start shows nothing: there is nothing new to someone who had nothing before.
 */
@Composable
fun WhatsNewPrompt(onOpenAll: () -> Unit) {
    val store = remember { settingsStore("whats-new") }
    var showing by remember { mutableStateOf<List<Changelog.Release>>(emptyList()) }
    val current = AppUpdates.current
    LaunchedEffect(Unit) {
        // A build that doesn't know its own version (run from the sources) has nothing to compare.
        if (current == "0.0.0") return@LaunchedEffect
        val seen = store.getString("seen", null)
        if (seen != null && compareVersions(seen, current) >= 0) return@LaunchedEffect
        val hasLibrary = seen == null && Library.ready &&
            runCatching { Library.store.libraryFlowOnce().isNotEmpty() }.getOrDefault(false)
        showing = Changelog.toShow(current, seen, hasLibrary, Changelog.releases).take(3)
        if (showing.isEmpty()) store.putString("seen", current)
    }
    if (showing.isEmpty()) return
    val done = { store.putString("seen", current); showing = emptyList() }
    AlertDialog(
        onDismissRequest = done,
        title = { Text(if (showing.size == 1) "New in ${showing[0].version}" else "What's new") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                showing.forEach { r -> ReleaseBody(r, header = showing.size > 1) }
            }
        },
        confirmButton = { TextButton(onClick = done) { Text("Got it") } },
        dismissButton = { TextButton(onClick = { done(); onOpenAll() }) { Text("Every version") } },
    )
}

@Composable
internal fun ReleaseCard(r: Changelog.Release) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cardColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) { ReleaseBody(r, header = true) }
    }
}

/** One release: its version and date, what it is about, then its sections. */
@Composable
private fun ReleaseBody(r: Changelog.Release, header: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (header) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.version, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (r.date.isNotBlank()) Text(
                longDate(r.date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
            if (compareVersions(r.version, AppUpdates.current) == 0) {
                Text(
                    "This version",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        r.intro.forEach { p ->
            Text(styled(p), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        r.sections.forEach { s -> SectionBlock(s) }
    }
}

@Composable
private fun SectionBlock(s: Changelog.Section) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        s.title?.let { title ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(title), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        s.blocks.forEach { b ->
            when (b) {
                is Changelog.Block.Item -> Row(Modifier.padding(start = 4.dp)) {
                    Box(
                        Modifier.padding(top = 8.dp, end = 10.dp).size(5.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                    Text(styled(b.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is Changelog.Block.Paragraph ->
                    Text(styled(b.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun styled(text: String): AnnotatedString {
    val strong = MaterialTheme.colorScheme.onSurface
    val codeBack = MaterialTheme.colorScheme.surfaceVariant
    return remember(text, strong, codeBack) {
        buildAnnotatedString {
            for (s in Changelog.spans(text)) {
                when {
                    s.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strong)) { append(s.text) }
                    s.italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
                    s.code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBack)) { append(s.text) }
                    else -> append(s.text)
                }
            }
        }
    }
}

/** A picture for a section, from the words in its title. */
private fun iconFor(title: String): ImageVector {
    val t = title.lowercase()
    return when {
        "sync" in t -> Icons.Filled.Sync
        "fix" in t -> Icons.Filled.Build
        "safe" in t || "secur" in t || "privacy" in t -> Icons.Filled.Shield
        "fast" in t || "light" in t || "speed" in t || "perform" in t -> Icons.Filled.Speed
        "narrat" in t || "voice" in t || "read aloud" in t || "expressive" in t || "tts" in t -> Icons.Filled.RecordVoiceOver
        "read" in t -> Icons.AutoMirrored.Filled.MenuBook
        "source" in t || "browse" in t || "extension" in t -> Icons.Filled.Extension
        "library" in t -> Icons.Filled.LocalLibrary
        "download" in t || "export" in t || "epub" in t -> Icons.Filled.Download
        else -> Icons.Filled.AutoAwesome
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2026-10-22" as "22 Oct 2026"; anything else as it is. */
internal fun longDate(iso: String): String {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(iso.trim()) ?: return iso
    val (y, mo, d) = m.destructured
    val month = MONTHS.getOrNull(mo.toInt() - 1) ?: return iso
    return "${d.toInt()} $month $y"
}
