package com.novelscraper.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.platform.openInBrowser
import com.novelscraper.app.ui.screen.GroupDivider
import com.novelscraper.app.ui.screen.ROW_TEXT_INSET
import com.novelscraper.app.ui.screen.SettingsRow
import com.novelscraper.app.ui.theme.Kicker
import com.novelscraper.app.update.AppUpdates

/**
 * The version running, and what to do about a newer one, as the rows of the
 * "This app" card in Settings.
 *
 * Updating used to mean going to the releases page and fetching a file by hand,
 * which is a thing nobody remembers to do. The app asks the same page itself.
 */
@Composable
fun UpdateSection(onChangelog: () -> Unit = {}) {
    val state by AppUpdates.state.collectAsState()
    UpdateRows(
        state = state,
        current = AppUpdates.current,
        onChangelog = onChangelog,
        onCheck = { AppUpdates.check() },
        onUpdate = { AppUpdates.download() },
        onInstall = { AppUpdates.install() },
        onDismiss = { AppUpdates.dismiss() },
        onReleasePage = { openInBrowser(AppUpdates.RELEASES_PAGE) },
    )
}

/**
 * The version row (what is running and where it stands, with Check), a panel
 * under it while there is a newer version (what's new, getting it, putting
 * it in place), and the changelog row. Every action of a state sits in one
 * place, so the card reads top to bottom: the panel used to put the notes
 * and their buttons below the changelog link, apart from the Update button.
 */
@Composable
internal fun UpdateRows(
    state: AppUpdates.State,
    current: String,
    onChangelog: () -> Unit,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    onReleasePage: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val (status, statusColor) = when (state) {
        is AppUpdates.State.Idle ->
            (if (state.upToDate) "Up to date" else "Updates come from the releases page") to c.onSurfaceVariant
        AppUpdates.State.Checking -> "Looking for a newer version…" to c.onSurfaceVariant
        is AppUpdates.State.Available -> "Version ${state.version} is available" to c.primary
        is AppUpdates.State.Downloading -> "Getting version ${state.version}" to c.primary
        is AppUpdates.State.Ready -> "Version ${state.version} is ready" to c.primary
        is AppUpdates.State.Failed -> state.message to c.error
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // Lined up with the row's text where there is room; the card's full width on a phone.
    val inset = if (maxWidth < 480.dp) 16.dp else ROW_TEXT_INSET
    Column(Modifier.fillMaxWidth()) {
        SettingsRow(
            title = "Version $current",
            summary = status,
            summaryColor = statusColor,
            icon = Icons.Filled.Update,
            trailing = when (state) {
                AppUpdates.State.Checking -> { { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp) } }
                is AppUpdates.State.Idle -> { { OutlinedButton(onClick = onCheck) { Text("Check") } } }
                is AppUpdates.State.Failed -> { { OutlinedButton(onClick = onCheck) { Text("Try again") } } }
                else -> null
            },
        )
        when (state) {
            is AppUpdates.State.Available -> UpdatePanel("New in ${state.version}", inset, link = {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onReleasePage).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Release page", style = MaterialTheme.typography.labelMedium, color = c.primary)
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.padding(start = 4.dp).size(14.dp), tint = c.primary)
                }
            }) {
                if (state.notes.isNotBlank()) {
                    Text(
                        state.notes, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6, overflow = TextOverflow.Ellipsis,
                    )
                }
                Actions {
                    Button(onClick = onUpdate) { Text("Update") }
                    TextButton(onClick = onDismiss) { Text("Not now") }
                }
            }
            is AppUpdates.State.Downloading -> UpdatePanel("Downloading", inset) {
                val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
                LinearProgressIndicator(
                    { fraction },
                    Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "${state.done / 1_000_000} of ${state.total / 1_000_000} MB",
                        style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant,
                    )
                }
            }
            is AppUpdates.State.Ready -> UpdatePanel("Ready to install", inset) {
                Text(
                    if (isDesktop) "The app closes, updates and opens again, where it was."
                    else "Downloaded. Android asks once more before installing.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Actions {
                    Button(onClick = onInstall) { Text(if (isDesktop) "Install and restart" else "Install") }
                }
            }
            else -> Unit
        }
        GroupDivider(inset = ROW_TEXT_INSET)
        SettingsRow(
            title = "Version history",
            summary = "What changed in each version",
            icon = Icons.AutoMirrored.Filled.Notes,
            onClick = onChangelog,
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.onSurfaceVariant) },
        )
    }
    }
}

/** A tinted panel under the version row, [inset] from the card's start,
 *  headed by [kicker] with an optional [link] at the other end. */
@Composable
private fun UpdatePanel(kicker: String, inset: Dp, link: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.padding(start = inset, end = 16.dp, bottom = 16.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                kicker.uppercase(),
                style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            link?.invoke()
        }
        content()
    }
}

/** A panel's buttons: in a line, wrapping on a narrow card. The text buttons
 *  are pulled in by their own padding, so their words line up with the panel's. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
