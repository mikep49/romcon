package dev.romcon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.romcon.AppViewModel
import dev.romcon.PairingUi
import dev.romcon.data.SavedPc
import dev.romcon.net.Proto
import dev.romcon.net.verifyCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel) {
    val pcs by vm.pcs.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    var addDialog by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedPc?>(null) }
    var forgetting by remember { mutableStateOf<SavedPc?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.discoverWhileVisible() }
    }

    val newFound = found.filter { f -> pcs.none { it.fingerprint == f.pc.fingerprint } }
    val online = found.map { it.pc.fingerprint }.toSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RomCon") },
                actions = { IconButton(onClick = vm::openSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add by address") },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (pcs.isNotEmpty()) {
                item { SectionTitle("Your PCs") }
                items(pcs, key = { it.id }) { pc ->
                    SavedPcCard(
                        pc = pc,
                        online = pc.fingerprint in online,
                        onClick = { vm.connect(pc) },
                        onEdit = { editing = pc },
                        onForget = { forgetting = pc },
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("On this Wi-Fi")
                    Spacer(Modifier.width(10.dp))
                    if (scanning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }
            if (newFound.isEmpty()) {
                item { EmptyHint(hasSaved = pcs.isNotEmpty()) }
            }
            items(newFound, key = { it.pc.fingerprint }) { f ->
                PcCard(
                    icon = Icons.Filled.Wifi,
                    title = f.pc.name,
                    subtitle = "${f.pc.host} · tap to pair",
                    onClick = { vm.pairDiscovered(f.pc) },
                )
            }
        }
    }

    if (addDialog) {
        AddressDialog(
            title = "Add a PC",
            initialHost = "",
            initialPort = Proto.DEFAULT_PORT,
            confirmLabel = "Connect",
            onConfirm = { host, port ->
                addDialog = false
                vm.addByAddress(host, port)
            },
            onDismiss = { addDialog = false },
        )
    }
    editing?.let { pc ->
        AddressDialog(
            title = "Edit address",
            initialHost = pc.host,
            initialPort = pc.port,
            confirmLabel = "Save",
            onConfirm = { host, port ->
                vm.editAddress(pc, host, port)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    forgetting?.let { pc ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${pc.name}?") },
            text = { Text("You'll need a new PIN from the PC to pair again. To also remove this phone from the PC, use Remove phone in RomCon there.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.forget(pc)
                    forgetting = null
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
    pairing?.let { PairingDialog(it, onSubmit = vm::submitPin, onRetry = vm::retryPairing, onCancel = vm::cancelPairing) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
    )
}

@Composable
private fun SavedPcCard(pc: SavedPc, online: Boolean, onClick: () -> Unit, onEdit: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val address = if (pc.port == Proto.DEFAULT_PORT) pc.host else "${pc.host}:${pc.port}"
    PcCard(
        icon = Icons.Filled.Computer,
        title = pc.name,
        subtitle = if (online) "On this Wi-Fi · $address" else address,
        highlight = online,
        onClick = onClick,
    ) {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${pc.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Edit address") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Forget") }, onClick = { menu = false; onForget() })
            }
        }
    }
}

@Composable
private fun PcCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    highlight: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(
                        if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing()
        }
    }
}

@Composable
private fun EmptyHint(hasSaved: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (hasSaved) "No other PCs found on this Wi-Fi." else "Looking for PCs running RomCon…",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!hasSaved) {
                Text(
                    "1. Start RomCon on your Windows PC.\n2. Keep this phone on the same Wi-Fi. The PC appears here; tap it to pair.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Using Tailscale, or the PC doesn't show up? Tap Add by address and enter the PC's Tailscale address (100.x.y.z), its name, or its LAN IP. The RomCon window on the PC lists them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddressDialog(
    title: String,
    initialHost: String,
    initialPort: Int,
    confirmLabel: String,
    onConfirm: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(initialHost) }
    var port by rememberSaveable { mutableStateOf(initialPort.toString()) }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = host.isNotBlank() && portNumber != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The PC's address on your network or on Tailscale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("Address or name") },
                    placeholder = { Text("100.64.0.5 or my-pc") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> port = v.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    isError = portNumber == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(host, portNumber) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(host, portNumber!!) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PairingDialog(p: PairingUi, onSubmit: (String) -> Unit, onRetry: () -> Unit, onCancel: () -> Unit) {
    var pin by rememberSaveable(p.host, p.port) { mutableStateOf("") }
    var lastTried by rememberSaveable(p.host, p.port) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val ready = p.fingerprint != null
    LaunchedEffect(ready) {
        if (ready) runCatching { focus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pair with ${p.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (p.fingerprint == null) {
                    if (p.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Connecting to ${p.host}…")
                        }
                    }
                } else {
                    Text("On the PC, click Pair a new phone in RomCon, then type the PIN it shows.")
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { v ->
                            val digits = v.filter(Char::isDigit).take(6)
                            pin = digits
                            // Submit each new full PIN once; a rejected PIN isn't resent until it's changed.
                            if (digits.length == 6 && digits != lastTried) {
                                lastTried = digits
                                onSubmit(digits)
                            }
                        },
                        label = { Text("PIN") },
                        singleLine = true,
                        enabled = !p.busy,
                        textStyle = LocalTextStyle.current.copy(fontSize = 22.sp, letterSpacing = 6.sp, fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (pin.length == 6) onSubmit(pin) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("PC code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(verifyCode(p.fingerprint), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                            Text(
                                "RomCon on the PC shows the same code. If it's different, cancel: something is between this phone and the PC.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (p.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (p.fingerprint == null) {
                TextButton(onClick = onRetry, enabled = !p.busy) { Text("Retry") }
            } else {
                TextButton(onClick = { onSubmit(pin) }, enabled = pin.length == 6 && !p.busy) { Text("Pair") }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
