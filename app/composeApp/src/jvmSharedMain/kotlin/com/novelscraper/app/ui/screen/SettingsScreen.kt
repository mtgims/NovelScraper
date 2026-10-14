package com.novelscraper.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.InputChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Close
import com.novelscraper.app.tts.NarrationText
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.library.LibrarySyncRunner
import com.novelscraper.app.ui.components.ContentWidth
import com.novelscraper.app.platform.isDesktop
import com.novelscraper.app.ui.components.UpdateSection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import com.novelscraper.app.ui.components.ColumnScrollbar
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.ButtonDefaults
import retrofit2.HttpException
import com.novelscraper.app.net.detail
import com.novelscraper.app.platform.showToast
import com.novelscraper.app.net.Account
import com.novelscraper.app.net.Net
import com.novelscraper.app.sync.FolderAuthException
import com.novelscraper.app.sync.SyncNewerFormatException
import com.novelscraper.app.sync.SyncNotEncryptedException
import com.novelscraper.app.sync.SyncPassphraseNeededException
import com.novelscraper.app.platform.firewallHint
import com.novelscraper.app.sync.LanSync
import com.novelscraper.app.sync.SyncCode
import com.novelscraper.app.sync.SyncCodeException
import com.novelscraper.app.sync.SyncSettings
import com.novelscraper.app.sync.SyncWrongPassphraseException
import com.novelscraper.app.platform.hasSystemTts
import com.novelscraper.app.tts.KokoroDownloader
import com.novelscraper.app.tts.TtsModels
import com.novelscraper.app.ui.components.KokoroVoicePicker
import com.novelscraper.app.ui.theme.Kicker
import com.novelscraper.app.ui.theme.THEMES
import com.novelscraper.app.ui.theme.ThemeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.foundation.text.selection.SelectionContainer

@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    val account by Account.state.collectAsState()
    val folder by SyncSettings.state.collectAsState()
    var deleting by remember { mutableStateOf(false) }
    val theme by ThemeController.theme.collectAsState()
    val rate by ReaderPrefs.ttsRate.collectAsState()
    val fontScale by ReaderPrefs.fontScale.collectAsState()

    val scroll = rememberScrollState()
    // Sent here to set sync up (the server sync notice): go down to it.
    LaunchedEffect(Unit) {
        if (SettingsHooks.jumpToSync) {
            SettingsHooks.jumpToSync = false
            snapshotFlow { scroll.maxValue }.first { it > 0 && it != Int.MAX_VALUE }
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    Box(Modifier.fillMaxSize()) {
      ContentWidth(Modifier.statusBarsPadding().verticalScroll(scroll)) {
      Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = if (isDesktop) 40.dp else 120.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Section("THIS APP")
        UpdateSection()

        Section("APPEARANCE")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            THEMES.forEach { t ->
                val selected = t.name == theme
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(t.swatchBg)
                            .border(
                                BorderStroke(if (selected) 3.dp else 1.dp,
                                    if (selected) t.accent else MaterialTheme.colorScheme.outline),
                                CircleShape,
                            )
                            .clickable { ThemeController.setTheme(t.name) },
                    ) {}
                    Text(t.label, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        Section("NARRATION")
        Text("Speech rate  ·  ${"%.1f".format(rate)}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = rate, onValueChange = { ReaderPrefs.setTtsRate(it) },
            valueRange = 0.5f..2.5f,
            modifier = Modifier.fillMaxWidth(),
        )
        NarrationEngine()
        SettingsHooks.narration?.invoke()
        NarrationWords()

        Section("READING")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Font size  ·  ${(fontScale * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { ReaderPrefs.decreaseFont() }) { Text("A-") }
            Text("  ")
            OutlinedButton(onClick = { ReaderPrefs.increaseFont() }) { Text("A+") }
        }

        Section("SYNC")
        FolderSyncSection()

        // Accounts are on their way out: shown only to someone still signed in.
        (account as? Account.State.SignedIn)?.let { a ->
            Section("SERVER ACCOUNT")
            Text(a.username.ifBlank { "Signed in" }, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(Net.baseUrl + if (a.online) "" else " · not reached yet", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (folder == null) SyncStatus()
            Row(
                Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onLogout) { Text("Sign out") }
                TextButton(
                    onClick = { deleting = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete account") }
            }
        }
        if (deleting) DeleteAccountDialog(onDismiss = { deleting = false })
    }
      }
      ColumnScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** Settings only one platform has, filled in by that platform when it starts
 *  (the desktop's GPU narration). */
object SettingsHooks {
    var narration: (@Composable () -> Unit)? = null
    /** Set before opening Settings to land on the Sync section. */
    var jumpToSync = false
}

/**
 * Engine picker (device / on-device Kokoro / on-device Piper) plus each neural
 * engine's model download. Downloading runs off the main thread; progress is
 * mirrored back to Compose state.
 */
/**
 * Sync: between the devices on the same Wi-Fi, paired by a sync code, or
 * through a WebDAV folder. What it is and a way to set it up, or how it is
 * doing.
 */
@Composable
private fun FolderSyncSection() {
    val conn by SyncSettings.state.collectAsState()
    var webdav by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }
    var addPeer by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    when (val c = conn) {
        null -> {
            Text(
                "Optional. Keeps the library in step between your devices whenever they are on the " +
                    "same Wi-Fi: the novels, what has been read, categories and where reading left " +
                    "off. Nothing leaves your network, and each device carries on by itself when apart.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            SyncSettings.connectLan(SyncCode.generate())
                            showCode = true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            showToast("Couldn't start syncing: ${e.message}")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Start syncing") }
                OutlinedButton(enabled = !busy, onClick = { joining = true }) { Text("Enter a sync code") }
            }
            TextButton(onClick = { webdav = true }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Or sync through WebDAV (Nextcloud, a NAS)")
            }
        }
        else -> {
            Text(c.provider.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(c.where + if (c.encrypted) " · encrypted" else "", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.provider == SyncSettings.Provider.LAN) LanStatus() else SyncStatus()
            FlowRow(
                Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (c.provider == SyncSettings.Provider.LAN) {
                    Button(onClick = { showCode = true }) { Text("Show sync code") }
                    OutlinedButton(onClick = { addPeer = true }) { Text("Add a device by address") }
                }
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try { SyncSettings.disconnect(); showToast("Sync is off. The library stays on this device.") }
                        finally { busy = false }
                    }
                }) { Text("Disconnect") }
                if (c.provider == SyncSettings.Provider.WEBDAV) {
                    TextButton(
                        enabled = !busy,
                        onClick = { deleting = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete synced data") }
                }
            }
            if (deleting) DeleteSyncedDataDialog(c, onDismiss = { deleting = false })
        }
    }
    if (webdav) WebDavDialog(onDismiss = { webdav = false })
    if (joining) JoinDialog(onDismiss = { joining = false })
    if (showCode) SyncCodeDialog(onDismiss = { showCode = false })
    if (addPeer) AddPeerDialog(onDismiss = { addPeer = false })
}

/** Same Wi-Fi: which devices this one has met, and how to be found. */
@Composable
private fun LanStatus() {
    val lan by SyncSettings.lan.collectAsState()
    val state = lan?.state?.collectAsState()?.value
    val sync by LibrarySyncRunner.state.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(15_000) }
    }
    val met = state?.peers.orEmpty().filter { it.lastSync > 0 }
    Column(Modifier.padding(top = 12.dp)) {
        when {
            sync.problem != null -> Text("Not synced. " + sync.problem, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
            met.isEmpty() -> {
                Text(
                    "Looking for your other devices on this Wi-Fi. Open NovelScraper on them to sync.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val hint by produceState<String?>(null) {
                    value = withContext(Dispatchers.IO) { firewallHint(LanSync.DISCOVERY_PORT to LanSync.SERVER_PORT) }
                }
                hint?.let { cmd ->
                    Text(
                        "This computer's firewall keeps the other devices out. To let them in from this " +
                            "network only, run in a terminal:",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    SelectionContainer {
                        Text(cmd, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            else -> met.forEach { p ->
                Text("Synced with ${p.name} ${ago(now, p.lastSync)}.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state?.addresses?.takeIf { it.isNotEmpty() }?.let { addresses ->
            Text("This device: ${addresses.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun ago(now: Long, then: Long): String = when (val mins = ((now - then) / 60_000).toInt()) {
    0 -> "just now"
    1 -> "a minute ago"
    in 2..59 -> "$mins minutes ago"
    else -> "${mins / 60} h ago"
}

@Composable
private fun SyncCodeDialog(onDismiss: () -> Unit) {
    val code = remember { SyncSettings.code()?.toString().orEmpty() }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync code") },
        text = {
            Column {
                Text(
                    code,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text(
                    "On the other device: Settings, Sync, Enter a sync code. Then keep both on the same " +
                        "Wi-Fi with NovelScraper open, and they find each other.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Anyone with this code can sync with your library, so keep it to yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)); showToast("Copied.") }) { Text("Copy") }
        },
    )
}

@Composable
private fun JoinDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Enter a sync code") },
        text = {
            Column {
                Text(
                    "The code another device shows under Settings, Sync, Show sync code.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("Sync code") },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                val code = try { SyncCode.parse(text) } catch (e: SyncCodeException) { error = e.message; return@TextButton }
                busy = true
                scope.launch {
                    try {
                        SyncSettings.connectLan(code)
                        showToast("Paired. The devices sync whenever they're on the same Wi-Fi.")
                        onDismiss()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "Couldn't start syncing: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Pairing…" else "Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** For networks that keep devices from hearing each other's broadcasts. */
@Composable
private fun AddPeerDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a device by address") },
        text = {
            Column {
                Text(
                    "Only needed if the devices don't find each other by themselves, which some " +
                        "networks prevent. The other device shows its address here, under Sync.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("Address, like 192.168.1.20:47812") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                try { SyncSettings.addPeer(text); onDismiss() } catch (e: IllegalArgumentException) { error = e.message }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What a failed connection attempt says. */
private fun connectError(e: Exception): String = when (e) {
    is IllegalArgumentException -> e.message ?: "That address isn't usable."
    is FolderAuthException -> "The server refused that username or password."
    is SyncWrongPassphraseException -> "That passphrase doesn't open this sync folder."
    is SyncPassphraseNeededException -> "This sync folder is encrypted. Enter its passphrase."
    is SyncNotEncryptedException -> "This sync folder was set up without a passphrase. Leave the passphrase empty."
    is SyncNewerFormatException -> e.message ?: "This sync folder needs a newer version of the app."
    else -> "Couldn't reach the server" + (e.message?.let { ": $it" } ?: ".")
}

@Composable
private fun WebDavDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    @Composable
    fun field(value: String, label: String, secret: Boolean = false, onChange: (String) -> Unit) =
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it); error = null },
            label = { Text(label) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Sync with WebDAV") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "The library goes in a NovelScraper folder at this address. On Nextcloud the " +
                        "address is under Files, Settings, WebDAV; an app password is better than " +
                        "the account's own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                field(address, "Address") { address = it }
                field(user, "Username") { user = it }
                field(password, "Password", secret = true) { password = it }
                field(passphrase, "Passphrase (optional)", secret = true) { passphrase = it }
                Text(
                    "A passphrase encrypts the library before it leaves this device. Every device " +
                        "needs the same one, and a forgotten passphrase can't be recovered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && address.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            SyncSettings.connectWebDav(address, user.trim(), password, passphrase)
                            showToast("Sync is on.")
                            onDismiss()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = connectError(e)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Connecting…" else "Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** Emptying the sync folder, which every device syncs through. */
@Composable
private fun DeleteSyncedDataDialog(c: SyncSettings.Connection, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete synced data?") },
        text = {
            Column {
                Text(
                    "The NovelScraper folder on ${c.where} is emptied, and this device stops syncing.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Every device keeps its own library. A device still connected to this folder " +
                        "fills it again the next time it syncs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            SyncSettings.deleteSyncedData()
                            showToast("Synced data deleted.")
                            onDismiss()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = connectError(e)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Deleting…" else "Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/**
 * Deleting the account for good. The password is asked for again because a
 * session left open is not enough for something irreversible, and the server
 * requires it too.
 */
@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete this account?") },
        text = {
            Column {
                Text(
                    "The account and everything it syncs are deleted from the server, " +
                        "for good. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Your library stays on this device and goes on working without an " +
                        "account. Your other devices keep what they already have, but " +
                        "will stop syncing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Your password") },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && password.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            Account.deleteAccount(password)
                            showToast("Account deleted.")
                            onDismiss()
                        } catch (e: HttpException) {
                            error = e.detail() ?: when (e.code()) {
                                401 -> "That password is not right."
                                400 -> "This is the last administrator account."
                                else -> "Couldn't delete the account (${e.code()})."
                            }
                        } catch (e: Exception) {
                            error = "Can't reach the server."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Deleting…" else "Delete for good") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

@Composable
private fun NarrationEngine() {
    val engine by ReaderPrefs.ttsEngine.collectAsState()

    Text("Engine", style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    // Four engines on a phone (its own voice too) don't fit one row: they wrap
    // instead of pushing the last off the screen and squeezing the others.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasSystemTts) {
            EngineChip("Device", engine == ReaderPrefs.ENGINE_DEVICE) {
                ReaderPrefs.setTtsEngine(ReaderPrefs.ENGINE_DEVICE)
            }
        }
        EngineChip("Kokoro", engine == ReaderPrefs.ENGINE_KOKORO) {
            ReaderPrefs.setTtsEngine(ReaderPrefs.ENGINE_KOKORO)
        }
        EngineChip("Supertonic", engine == ReaderPrefs.ENGINE_SUPERTONIC) {
            ReaderPrefs.setTtsEngine(ReaderPrefs.ENGINE_SUPERTONIC)
        }
        EngineChip("Piper", engine == ReaderPrefs.ENGINE_PIPER) {
            ReaderPrefs.setTtsEngine(ReaderPrefs.ENGINE_PIPER)
        }
    }

    when (engine) {
        ReaderPrefs.ENGINE_KOKORO -> NeuralModel(ReaderPrefs.ENGINE_KOKORO)
        ReaderPrefs.ENGINE_SUPERTONIC -> NeuralModel(ReaderPrefs.ENGINE_SUPERTONIC)
        ReaderPrefs.ENGINE_PIPER -> PiperVoiceList()
    }
}

/** Piper voices, grouped by accent. Tap a downloaded voice to use it; tap an
 *  un-downloaded one to fetch it (~65 MB each) and then use it. */
@Composable
private fun PiperVoiceList() {
    val scope = rememberCoroutineScope()
    val selected by ReaderPrefs.piperVoice.collectAsState()
    var busyId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<KokoroDownloader.Progress?>(null) }
    var tick by remember { mutableStateOf(0) } // bump to re-check installed after a download

    Text("Fast English voices · ~65 MB each, downloaded on demand.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp))

    TtsModels.PIPER_VOICES.groupBy { it.accent }.forEach { (accent, voices) ->
        Text(accent.uppercase(),
            style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
        voices.forEach { v ->
            val installed = remember(tick, v.id) { TtsModels.isModelReady(v.id) }
            val busy = busyId == v.id
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = busyId == null) {
                        if (installed) {
                            ReaderPrefs.setPiperVoice(v.id)
                        } else {
                            busyId = v.id
                            progress = KokoroDownloader.Progress.Downloading(0, 1)
                            scope.launch(Dispatchers.IO) {
                                KokoroDownloader.download(Net.client, v.id) { p ->
                                    scope.launch(Dispatchers.Main) {
                                        progress = p
                                        when (p) {
                                            is KokoroDownloader.Progress.Done -> {
                                                busyId = null; tick++; ReaderPrefs.setPiperVoice(v.id)
                                            }
                                            is KokoroDownloader.Progress.Failed -> busyId = null
                                            else -> {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${v.name} · ${v.gender}", style = MaterialTheme.typography.bodyMedium)
                    if (busy) {
                        val p = progress
                        val lbl = when (p) {
                            is KokoroDownloader.Progress.Downloading ->
                                "Downloading ${p.bytes / 1_000_000} / ${p.total / 1_000_000} MB"
                            is KokoroDownloader.Progress.Extracting -> "Extracting…"
                            else -> "…"
                        }
                        Text(lbl, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when {
                    busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    installed && v.id == selected ->
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    installed ->
                        Text("Use", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    else ->
                        Text("Download", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    (progress as? KokoroDownloader.Progress.Failed)?.let {
        Text("Download failed: ${it.message}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Download state + (for Kokoro) the voice picker, for one neural engine. */
@Composable
private fun NeuralModel(engine: String) {
    val scope = rememberCoroutineScope()
    val speaker by ReaderPrefs.kokoroSpeaker.collectAsState()
    val supertonicSpeaker by ReaderPrefs.supertonicSpeaker.collectAsState()

    var installed by remember(engine) { mutableStateOf(TtsModels.isModelReady(engine)) }
    var progress by remember(engine) { mutableStateOf<KokoroDownloader.Progress?>(null) }
    val downloading = progress is KokoroDownloader.Progress.Downloading ||
        progress is KokoroDownloader.Progress.Extracting

    val blurb = when (engine) {
        ReaderPrefs.ENGINE_KOKORO ->
            if (isDesktop) "~335 MB · multilingual, most natural (English, Spanish, French, Chinese, Japanese…), " +
                "at full precision. About real time."
            else "~125 MB · multilingual, most natural (English, Spanish, French, Chinese, Japanese…). ~1× real time."
        ReaderPrefs.ENGINE_SUPERTONIC ->
            "~122 MB · ten voices, nearly as natural as Kokoro and several times faster, light on the " +
                "processor: made for phones. Reads English for now."
        else ->
            "~65 MB · fast English voice (Amy) — several times real time, no buffering; less expressive than Kokoro."
    }

    when {
        downloading -> {
            val p = progress
            val label = when (p) {
                is KokoroDownloader.Progress.Downloading ->
                    "Downloading  ${p.bytes / 1_000_000} / ${p.total / 1_000_000} MB"
                else -> "Extracting…"
            }
            Text(label, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            val frac = (progress as? KokoroDownloader.Progress.Downloading)
                ?.let { it.bytes.toFloat() / it.total.coerceAtLeast(1) }
            if (frac != null) {
                LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        installed -> {
            Text("Voice model installed", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
            if (engine == ReaderPrefs.ENGINE_KOKORO) {
                KokoroVoicePicker(currentId = speaker) { ReaderPrefs.setKokoroSpeaker(it) }
            } else if (engine == ReaderPrefs.ENGINE_SUPERTONIC) {
                com.novelscraper.app.ui.components.SupertonicVoicePicker(currentId = supertonicSpeaker) {
                    ReaderPrefs.setSupertonicSpeaker(it)
                }
            } else {
                Text("Voice · Amy (US English)", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        else -> {
            (progress as? KokoroDownloader.Progress.Failed)?.let {
                Text("Download failed: ${it.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp))
            }
            Text(blurb, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            Button(onClick = {
                progress = KokoroDownloader.Progress.Downloading(0, 1)
                scope.launch(Dispatchers.IO) {
                    KokoroDownloader.download(Net.client, engine) { p ->
                        scope.launch(Dispatchers.Main) {
                            progress = p
                            if (p is KokoroDownloader.Progress.Done) installed = true
                        }
                    }
                }
            }) { Text("Download voice model") }
        }
    }
}

@Composable
private fun EngineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // One line whatever the width: a name broken over two lines turned the
    // pill into a circle.
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp)
    val text: @Composable () -> Unit = { Text(label, maxLines = 1, softWrap = false) }
    if (selected) {
        Button(onClick = onClick, contentPadding = padding) { text() }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = padding) { text() }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = Kicker.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
    )
}

/** When the library last synced with the other devices, and a way to sync now. */
@Composable
private fun SyncStatus() {
    val sync by LibrarySyncRunner.state.collectAsState()
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sync.lastSync) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) }
    }
    val text = when {
        sync.syncing -> "Syncing…"
        sync.unsupported ->
            "Not synced: this server is an older build without sync. Update the server, then it will " +
                "catch up on its own. Nothing is lost meanwhile."
        sync.problem != null -> "Not synced. " + sync.problem
        sync.error -> "Not synced: can't reach " + (if (SyncSettings.connected) "the storage" else "the server") +
            ". Changes are kept and sent later."
        sync.lastSync == 0L -> "Library sync: waiting for the first sync."
        else -> {
            val mins = ((now - sync.lastSync) / 60_000).toInt()
            "Library synced " + when (mins) { 0 -> "just now"; 1 -> "a minute ago"; else -> "$mins minutes ago" } + "."
        }
    }
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = if (sync.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        TextButton(onClick = { scope.launch { LibrarySyncRunner.now() } }, enabled = !sync.syncing) { Text("Sync now") }
    }
}

/**
 * What narration says: the asides it passes over (site plugs, patron links,
 * translator notes, plus the reader's own lines) and how particular words should
 * sound.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NarrationWords() {
    val skipJunk by ReaderPrefs.ttsSkipJunk.collectAsState()
    val patterns by ReaderPrefs.ttsJunkPatterns.collectAsState()
    val terms by ReaderPrefs.ttsDictionary.collectAsState()
    var addPattern by remember { mutableStateOf(false) }
    var addTerm by remember { mutableStateOf<NarrationText.Term?>(null) }

    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Skip asides", style = MaterialTheme.typography.bodyMedium)
            Text("Site plugs, patron links and translator notes aren't read aloud.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = skipJunk, onCheckedChange = { ReaderPrefs.setTtsSkipJunk(it) })
    }

    if (skipJunk) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            patterns.forEach { p ->
                InputChip(
                    selected = false,
                    onClick = { ReaderPrefs.setTtsJunkPatterns(patterns - p) },
                    label = { Text(p) },
                    trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) },
                )
            }
            AssistChip(onClick = { addPattern = true }, label = { Text("Add a line to skip") })
        }
    }

    Text("Pronunciation", style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 16.dp))
    Text("Say a word the way you want to hear it.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        terms.forEach { t ->
            InputChip(
                selected = false,
                onClick = { addTerm = t },
                label = { Text("${t.from} → ${t.to}") },
                trailingIcon = {
                    Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp).clickable {
                        ReaderPrefs.setTtsDictionary(terms - t)
                    })
                },
            )
        }
        AssistChip(onClick = { addTerm = NarrationText.Term("", "") }, label = { Text("Add a word") })
    }

    if (addPattern) {
        TextPrompt(
            title = "Skip lines containing",
            label = "Text",
            initial = "",
            onDone = { text ->
                if (text.isNotBlank()) ReaderPrefs.setTtsJunkPatterns(patterns + text.trim())
                addPattern = false
            },
            onDismiss = { addPattern = false },
        )
    }
    addTerm?.let { editing ->
        TermPrompt(
            term = editing,
            onDone = { t ->
                ReaderPrefs.setTtsDictionary(terms.filter { it.from != editing.from && it.from != t.from } + t)
                addTerm = null
            },
            onDismiss = { addTerm = null },
        )
    }
}

@Composable
private fun TextPrompt(
    title: String,
    label: String,
    initial: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(label) })
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TermPrompt(
    term: NarrationText.Term,
    onDone: (NarrationText.Term) -> Unit,
    onDismiss: () -> Unit,
) {
    var from by remember { mutableStateOf(term.from) }
    var to by remember { mutableStateOf(term.to) }
    var whole by remember { mutableStateOf(term.wholeWord) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How to say a word") },
        text = {
            Column {
                OutlinedTextField(value = from, onValueChange = { from = it }, singleLine = true,
                    label = { Text("Written") })
                OutlinedTextField(value = to, onValueChange = { to = it }, singleLine = true,
                    label = { Text("Said as") }, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Whole word only", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Switch(checked = whole, onCheckedChange = { whole = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDone(NarrationText.Term(from.trim(), to.trim(), whole)) },
                enabled = from.isNotBlank() && to.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
