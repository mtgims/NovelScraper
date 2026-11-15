package com.novelscraper.app.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelscraper.app.library.LibrarySyncRunner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.ButtonDefaults
import com.novelscraper.app.platform.CodeScannerDialog
import com.novelscraper.app.ui.components.QrCode
import com.novelscraper.app.platform.canScanCodes
import com.novelscraper.app.platform.showToast
import com.novelscraper.app.sync.FolderAuthException
import com.novelscraper.app.sync.SyncNewerFormatException
import com.novelscraper.app.sync.SyncNotEncryptedException
import com.novelscraper.app.sync.SyncPassphraseNeededException
import com.novelscraper.app.platform.FirewallAdvice
import com.novelscraper.app.platform.firewallHint
import com.novelscraper.app.sync.LanSync
import com.novelscraper.app.sync.SyncCode
import com.novelscraper.app.sync.SyncCodeException
import com.novelscraper.app.sync.SyncSettings
import com.novelscraper.app.sync.SyncWrongPassphraseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.foundation.text.selection.SelectionContainer

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight

/**
 * How sync stands, for the list of settings: off, synced and when, or what is
 * wrong. True with it when something is wrong, to be shown as a problem.
 */
@Composable
internal fun syncSummary(): Pair<String, Boolean> {
    val conn by SyncSettings.state.collectAsState()
    val sync by LibrarySyncRunner.state.collectAsState()
    val lan by SyncSettings.lan.collectAsState()
    val state = lan?.state?.collectAsState()?.value
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(15_000) }
    }
    val c = conn ?: return "Off" to false
    if (sync.problem != null) return "Not synced. ${sync.problem}" to true
    if (c.provider != SyncSettings.Provider.LAN) return when {
        sync.syncing -> "Syncing…" to false
        sync.error -> "Can't reach the storage. Changes are kept and sent later." to true
        sync.lastSync == 0L -> "Waiting for the first sync" to false
        else -> "Synced ${ago(now, sync.lastSync)}" to false
    }
    val peers = state?.peers.orEmpty()
    // The same test as LanStatus: heard lately, turning this device away, not met another way since.
    peers.firstOrNull { it.unreachable && now - it.lastSeen < 3 * 60_000 && now - it.lastSync > 5 * 60_000 }
        ?.let { return "Found ${it.name}, but it won't let this device connect" to true }
    val last = peers.filter { it.lastSync > 0 }.maxByOrNull { it.lastSync }
        ?: return "Looking for your other devices on this Wi-Fi" to false
    return "Synced with ${last.name} ${ago(now, last.lastSync)}" to false
}

/**
 * Sync: between the devices on the same Wi-Fi, paired by a sync code, or
 * through a WebDAV folder. What it is and a way to set it up, or how it is
 * doing.
 */
@Composable
internal fun SyncPage() {
    val conn by SyncSettings.state.collectAsState()
    var webdav by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    /** A code read by the camera, for the join dialog to show before pairing. */
    var scanned by remember { mutableStateOf("") }
    var showCode by remember { mutableStateOf(false) }
    var addPeer by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val chevron: @Composable () -> Unit = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    when (val c = conn) {
        null -> {
            SettingsGroup {
                GroupBody {
                    Text("Keep your devices in step", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        "Optional. Keeps the library in step between your devices whenever they are on the " +
                            "same Wi-Fi: the novels, what has been read, categories and where reading left " +
                            "off. Nothing leaves your network, and each device carries on by itself when apart.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Button(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        onClick = {
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
                        },
                    ) { Text("Start syncing") }
                }
            }
            SettingsGroup(title = "Join a device that already syncs") {
                SettingsRow(
                    title = "Enter a sync code",
                    summary = "The code the other device shows",
                    icon = Icons.Outlined.Keyboard,
                    enabled = !busy,
                    onClick = { scanned = ""; joining = true },
                    trailing = chevron,
                )
                if (canScanCodes) {
                    GroupDivider(inset = ROW_TEXT_INSET)
                    SettingsRow(
                        title = "Scan a code",
                        summary = "Point the camera at the other device's code",
                        icon = Icons.Outlined.QrCodeScanner,
                        enabled = !busy,
                        onClick = { scanning = true },
                        trailing = chevron,
                    )
                }
            }
            SettingsGroup(title = "Another way") {
                SettingsRow(
                    title = "Sync through WebDAV",
                    summary = "A Nextcloud or NAS folder, for devices that are rarely on the same Wi-Fi",
                    icon = Icons.Outlined.Cloud,
                    onClick = { webdav = true },
                    trailing = chevron,
                )
            }
        }
        else -> {
            SettingsGroup(title = "Status") {
                GroupBody {
                    Text(c.provider.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(c.where + if (c.encrypted) " · encrypted" else "", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (c.provider == SyncSettings.Provider.LAN) LanStatus() else SyncStatus()
                }
            }
            SettingsGroup(title = "Devices") {
                if (c.provider == SyncSettings.Provider.LAN) {
                    SettingsRow(
                        title = "Show sync code",
                        summary = "To pair another device",
                        icon = Icons.Outlined.QrCode2,
                        onClick = { showCode = true },
                        trailing = chevron,
                    )
                    GroupDivider(inset = ROW_TEXT_INSET)
                    SettingsRow(
                        title = "Add a device by address",
                        summary = "When one isn't found by itself",
                        icon = Icons.Outlined.Lan,
                        onClick = { addPeer = true },
                        trailing = chevron,
                    )
                    GroupDivider(inset = ROW_TEXT_INSET)
                }
                SettingsRow(
                    title = "Disconnect",
                    summary = "Stop syncing. The library stays on this device.",
                    icon = Icons.Outlined.LinkOff,
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try { SyncSettings.disconnect(); showToast("Sync is off. The library stays on this device.") }
                            finally { busy = false }
                        }
                    },
                )
                if (c.provider == SyncSettings.Provider.WEBDAV) {
                    GroupDivider(inset = ROW_TEXT_INSET)
                    SettingsRow(
                        title = "Delete synced data",
                        summary = "Remove the library from the WebDAV folder",
                        icon = Icons.Outlined.DeleteOutline,
                        titleColor = MaterialTheme.colorScheme.error,
                        enabled = !busy,
                        onClick = { deleting = true },
                    )
                }
            }
            if (deleting) DeleteSyncedDataDialog(c, onDismiss = { deleting = false })
        }
    }
    if (webdav) WebDavDialog(onDismiss = { webdav = false })
    if (joining) JoinDialog(initial = scanned, onDismiss = { joining = false })
    if (scanning) CodeScannerDialog(
        onScanned = { text ->
            scanning = false
            // Any QR code reads; only a sync code goes on to pairing.
            if (runCatching { SyncCode.parse(text) }.isSuccess) { scanned = text; joining = true }
            else showToast("That QR code isn't a sync code. Show the sync code on the other device and scan that.", long = true)
        },
        onDismiss = { scanning = false },
    )
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
    // Heard lately, but turning this device away, and not met another way since.
    val turnedAway = state?.peers.orEmpty().filter {
        it.unreachable && now - it.lastSeen < 3 * 60_000 && now - it.lastSync > 5 * 60_000
    }
    Column(Modifier.padding(top = 12.dp)) {
        turnedAway.forEach { p ->
            Text(
                "Found ${p.name} (${p.address.substringBeforeLast(':')}), but it won't let this device connect, " +
                    "most likely because of its firewall. If it's a Linux computer, Settings, Sync on it shows " +
                    "how to let devices in.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        when {
            sync.problem != null -> Text("Not synced. " + sync.problem, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
            met.isEmpty() -> {
                if (turnedAway.isEmpty()) Text(
                    "Looking for your other devices on this Wi-Fi. Open NovelScraper on them to sync.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FirewallHint()
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

/** On a computer whose firewall keeps the other devices out: how to let them in. */
@Composable
private fun FirewallHint() {
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val advice by produceState<FirewallAdvice?>(null, checks) {
        value = withContext(Dispatchers.IO) { firewallHint(LanSync.DISCOVERY_PORT to LanSync.SERVER_PORT) }
    }
    val a = advice ?: return
    Text(
        if (a.elsewhere.isNotEmpty())
            "This computer's firewall lets the other devices in from ${a.elsewhere.joinToString(", ")}, but it is " +
                "on ${a.network} now. Until they're let in, they sync only when this computer calls them."
        else "This computer's firewall keeps the other devices out. Until they're let in, they sync only when " +
            "this computer calls them.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (a.canApply) {
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { a.apply() }
                busy = false
                showToast(if (ok) "Done. Your devices can reach this computer on ${a.network} now." else "Nothing was changed.")
                checks++
                if (ok) LibrarySyncRunner.soon()
            }
        }) { Text(if (busy) "Waiting for the password…" else "Let them in from ${a.network}") }
    }
    Text(
        if (a.canApply) "Or run this in a terminal:" else "To let them in from ${a.network} only, run in a terminal:",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    SelectionContainer {
        Text(a.command, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(top = 4.dp))
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
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (code.isNotEmpty()) QrCode(code, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp))
                Text(
                    code,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text(
                    "On the other device: Settings, Sync, then Scan a code on a phone, or Enter a sync " +
                        "code. Then keep both on the same Wi-Fi with NovelScraper open, and they find each other.",
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
private fun JoinDialog(initial: String = "", onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Enter a sync code") },
        text = {
            Column {
                Text(
                    if (initial.isNotEmpty()) "The code just scanned. Pair with it?"
                    else "The code another device shows under Settings, Sync, Show sync code.",
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
        sync.problem != null -> "Not synced. " + sync.problem
        sync.error -> "Not synced: can't reach the storage. Changes are kept and sent later."
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
