package dev.romcon.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.romcon.AppViewModel
import dev.romcon.ConnState
import dev.romcon.SettingsPage
import dev.romcon.data.AppSettings
import dev.romcon.data.BarButton
import dev.romcon.data.BarItem
import dev.romcon.data.ThemeMode
import dev.romcon.data.Transport
import dev.romcon.media.LinkStatus
import dev.romcon.media.MediaLink
import dev.romcon.update.UpdateStage
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val page = vm.settingsPage

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (page == SettingsPage.SHORTCUT) vm.shortcutEditorTitle else page?.title ?: "Settings") },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        // Keyed by page, so every page opens scrolled to the top.
        key(page) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (page) {
                    null -> SettingsHome(vm, s)
                    SettingsPage.APPEARANCE -> AppearanceSettings(vm, s)
                    SettingsPage.MOUSE -> MouseSettings(vm, s)
                    SettingsPage.KEYBOARD -> KeyboardSettings(vm, s)
                    SettingsPage.BOTTOM_BAR -> BottomBarSettings(vm, s)
                    SettingsPage.SHORTCUT -> ShortcutEditor(vm, s)
                    SettingsPage.MEDIA -> MediaSettings(vm, s)
                    SettingsPage.CONNECTION -> ConnectionSettings(vm, s)
                    SettingsPage.GENERAL -> GeneralSettings(vm, s)
                }
                Spacer(Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

private val SettingsPage.title: String
    get() = when (this) {
        SettingsPage.APPEARANCE -> "Appearance"
        SettingsPage.MOUSE -> "Mouse"
        SettingsPage.KEYBOARD -> "Keyboard"
        SettingsPage.BOTTOM_BAR -> "Bottom bar"
        SettingsPage.SHORTCUT -> "Shortcut"
        SettingsPage.MEDIA -> "Media controls"
        SettingsPage.CONNECTION -> "Connection"
        SettingsPage.GENERAL -> "General"
    }

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "System default"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
        ThemeMode.OLED -> "OLED black"
    }

@Composable
private fun SettingsHome(vm: AppViewModel, s: AppSettings) {
    PageLink(Icons.Filled.Palette, "Appearance", "Theme: ${s.theme.label}") { vm.openSettingsPage(SettingsPage.APPEARANCE) }
    PageLink(Icons.Filled.Mouse, "Mouse", "Pointer speed, scrolling, tap to click") { vm.openSettingsPage(SettingsPage.MOUSE) }
    PageLink(Icons.Filled.Keyboard, "Keyboard", "Modifier keys and typing") { vm.openSettingsPage(SettingsPage.KEYBOARD) }
    PageLink(Icons.Filled.TouchApp, "Bottom bar", s.bottomBar.joinToString(", ") { s.titleOf(it) }) { vm.openSettingsPage(SettingsPage.BOTTOM_BAR) }
    PageLink(
        Icons.Filled.PlayCircle,
        "Media controls",
        if (s.lockScreenMedia) "On the lock screen" else "Off",
    ) { vm.openSettingsPage(SettingsPage.MEDIA) }
    PageLink(Icons.Filled.SwapVert, "Connection", "Pointer movement over ${s.transport.name}") { vm.openSettingsPage(SettingsPage.CONNECTION) }
    PageLink(Icons.Filled.Tune, "General", "Vibration, keep screen on, lock button") { vm.openSettingsPage(SettingsPage.GENERAL) }

    Section("Updates")
    UpdateSettings(vm)

    Section("About")
    Help("RomCon ${vm.appVersion}\nThe PC sees this phone as \"${vm.deviceName}\".")
}

@Composable
private fun UpdateSettings(vm: AppViewModel) {
    val u by vm.update.collectAsStateWithLifecycle()
    val current = vm.appVersion
    val latest = u.latest?.version
    if (u.stage == UpdateStage.FAILED) {
        Text(
            u.error.orEmpty() + if (u.hasUpdate) " Version $latest is available." else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        Help(
            when (u.stage) {
                UpdateStage.CHECKING -> "Checking for updates…"
                UpdateStage.UP_TO_DATE -> "You have the latest version, $current."
                UpdateStage.AVAILABLE -> "Version $latest is available. You have $current."
                UpdateStage.DOWNLOADING -> "Downloading version $latest…"
                else -> "Version $current."
            },
        )
    }
    if (u.stage == UpdateStage.DOWNLOADING) {
        val progress = u.progress
        val bar = Modifier.fillMaxWidth().padding(top = 8.dp)
        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = bar) else LinearProgressIndicator(bar)
    }
    FlowRow(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (u.hasUpdate) {
            Button(onClick = vm::installUpdate, enabled = !u.busy) { Text("Update to $latest") }
        }
        OutlinedButton(onClick = vm::checkForUpdates, enabled = !u.busy) { Text("Check for updates") }
    }
}

@Composable
private fun AppearanceSettings(vm: AppViewModel, s: AppSettings) {
    Section("Theme")
    Column(Modifier.selectableGroup()) {
        ThemeMode.entries.forEach { mode ->
            val summary = if (mode == ThemeMode.OLED) "Pure black background" else null
            RadioSetting(mode.label, summary, selected = s.theme == mode) { vm.updateSettings { it.copy(theme = mode) } }
        }
    }
}

@Composable
private fun MouseSettings(vm: AppViewModel, s: AppSettings) {
    Section("Pointer")
    SliderSetting("Pointer speed", s.pointerSpeed, 0.3f..3f, "%.1f×") { v -> vm.updateSettings { it.copy(pointerSpeed = v) } }
    SliderSetting("Acceleration", s.acceleration, 0f..1.5f, "%.1f") { v -> vm.updateSettings { it.copy(acceleration = v) } }
    SwitchSetting("Tap to click", null, s.tapToClick) { v -> vm.updateSettings { it.copy(tapToClick = v) } }
    if (!s.tapToClick && BarItem.Builtin(BarButton.LEFT_CLICK) !in s.bottomBar) {
        Help("To click, add Left click to the bottom bar.")
    }

    Section("Scrolling")
    SliderSetting("Scroll speed", s.scrollSpeed, 0.3f..3f, "%.1f×") { v -> vm.updateSettings { it.copy(scrollSpeed = v) } }
    SwitchSetting("Natural scrolling", "Content moves with your fingers", s.naturalScroll) { v ->
        vm.updateSettings { it.copy(naturalScroll = v) }
    }
    SwitchSetting("Smooth scrolling", "Turn off if some apps don't scroll", s.smoothScroll) { v ->
        vm.updateSettings { it.copy(smoothScroll = v) }
    }
    SwitchSetting("Momentum", "Keeps scrolling after a quick swipe", s.scrollMomentum) { v ->
        vm.updateSettings { it.copy(scrollMomentum = v) }
    }
    SwitchSetting("Scroll bar", "Slide along the right edge of the trackpad", s.scrollBar) { v ->
        vm.updateSettings { it.copy(scrollBar = v) }
    }
    if (s.scrollBar) {
        SwitchSetting("Reverse the scroll bar", null, s.scrollBarReversed) { v -> vm.updateSettings { it.copy(scrollBarReversed = v) } }
    }

    Section("Gestures")
    Help(
        "Two-finger tap: right-click\n" +
            "Three-finger tap: middle-click\n" +
            "Two fingers: scroll\n" +
            "Double-tap and drag: drag",
    )
}

@Composable
private fun KeyboardSettings(vm: AppViewModel, s: AppSettings) {
    Section("Typing")
    SwitchSetting("Show what you type", "Above the phone keyboard", s.typingPreview) { v -> vm.updateSettings { it.copy(typingPreview = v) } }
    SwitchSetting("Close keyboard on Enter", "Turn off to type several lines", s.hideKeyboardOnEnter) { v ->
        vm.updateSettings { it.copy(hideKeyboardOnEnter = v) }
    }

    Section("Modifier keys")
    Help("Tap Ctrl, Alt, Shift or Win to use it with the next key, like Ctrl then C to copy. Long-press to keep it on.")
}

@Composable
private fun BottomBarSettings(vm: AppViewModel, s: AppSettings) {
    val bar = s.bottomBar
    val setBar: (List<BarItem>) -> Unit = { items -> vm.updateSettings { it.copy(bottomBar = items) } }
    val full = bar.size >= BarItem.MAX

    Help("Up to ${BarItem.MAX} buttons fit under the trackpad.")

    Section("On the bar")
    bar.forEachIndexed { i, item ->
        val title = s.titleOf(item)
        // Keyed, so the row keeps its identity (and its focus) as it moves up or down.
        key(item) {
            BarItemRow(s, item, onClick = (item as? BarItem.Custom)?.let { c -> s.shortcut(c.id)?.let { sc -> { vm.editShortcut(sc) } } }) {
                IconButton(onClick = { setBar(bar.swapped(i, i - 1)) }, enabled = i > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move $title left")
                }
                IconButton(onClick = { setBar(bar.swapped(i, i + 1)) }, enabled = i < bar.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move $title right")
                }
                // The bar always keeps one button, so there's never an empty strip.
                IconButton(onClick = { setBar(bar - item) }, enabled = bar.size > 1) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove $title")
                }
            }
        }
    }

    Section("Your shortcuts")
    Help("Buttons that press the keys you choose on the PC, like Alt+Space to open a launcher.")
    s.shortcuts.forEach { sc ->
        val item = BarItem.Custom(sc.id)
        key(item) {
            BarItemRow(s, item, onClick = { vm.editShortcut(sc) }) {
                IconButton(onClick = { vm.editShortcut(sc) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${sc.title}")
                }
                if (item !in bar) {
                    IconButton(onClick = { setBar(bar + item) }, enabled = !full) {
                        Icon(Icons.Filled.Add, contentDescription = "Add ${sc.title} to the bar")
                    }
                }
            }
        }
    }
    ActionRow(Icons.Filled.Add, "New shortcut", onClick = vm::newShortcut)

    Section("More buttons")
    if (full) Help("The bar is full.")
    BarButton.entries.map(BarItem::Builtin).filterNot { it in bar }.forEach { item ->
        key(item) {
            BarItemRow(s, item) {
                IconButton(onClick = { setBar(bar + item) }, enabled = !full) {
                    Icon(Icons.Filled.Add, contentDescription = "Add ${item.button.title}")
                }
            }
        }
    }

    Spacer(Modifier.padding(top = 8.dp))
    ActionRow(Icons.Filled.RestartAlt, "Reset to default", enabled = bar != BarItem.DEFAULT, onClick = { setBar(BarItem.DEFAULT) })
}

private fun <T> List<T>.swapped(a: Int, b: Int): List<T> = toMutableList().also {
    val moved = it[a]
    it[a] = it[b]
    it[b] = moved
}

/** A button as Settings lists it. Tapping a custom shortcut's row ([onClick]) opens it for editing. */
@Composable
private fun BarItemRow(s: AppSettings, item: BarItem, onClick: (() -> Unit)? = null, controls: @Composable () -> Unit) {
    val shortcut = (item as? BarItem.Custom)?.let { s.shortcut(it.id) }
    val summary = when (item) {
        is BarItem.Builtin -> item.button.summary
        // With no name, the title already shows the keys, though not the waits between them.
        is BarItem.Custom -> shortcut?.takeIf { it.name.isNotBlank() || it.waits }?.keysText
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Only the name is clickable, so the buttons beside it keep their own labels for screen readers.
        Row(
            Modifier
                .weight(1f)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = MaterialTheme.colorScheme.primary
            when {
                item is BarItem.Builtin -> Icon(item.button.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                shortcut != null -> ShortcutGlyph(shortcut, tint)
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text(s.titleOf(item), style = MaterialTheme.typography.bodyLarge)
                summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        controls()
    }
}

@Composable
private fun MediaSettings(vm: AppViewModel, s: AppSettings) {
    val status by MediaLink.state.collectAsStateWithLifecycle()
    val pcs by vm.pcs.collectAsStateWithLifecycle()

    SwitchSetting(
        "Show on the lock screen",
        "Play, pause, skip and volume while the PC plays something",
        s.lockScreenMedia,
    ) { v -> vm.updateSettings { it.copy(lockScreenMedia = v) } }
    if (!s.lockScreenMedia) return

    SwitchSetting("Volume buttons control the PC", "While it's playing", s.volumeKeys) { v -> vm.updateSettings { it.copy(volumeKeys = v) } }

    Section("Status")
    val outdated = pcs.filter(vm::lacksMedia)
    val linked = status as? LinkStatus.Connected
    Help(
        when {
            pcs.isEmpty() -> "Pair a PC first."
            linked != null && linked.showing -> "Showing what ${linked.pcName} is playing."
            linked != null -> "Connected to ${linked.pcName}. The controls appear when it plays something."
            outdated.size == pcs.size -> "Nothing to connect to yet."
            else -> "Looking for your PC. The controls appear once it's on and playing something."
        },
    )
    if (outdated.isNotEmpty()) {
        Help("RomCon on ${outdated.joinToString(", ") { it.name }} is too old for this. Update it, then connect to it once.")
    }

    Section("In the background")
    Help("RomCon stays connected to see what's playing. It uses very little battery.")
    BackgroundNotificationNote()
}

/** The name of a permission that exists from Android 13; older versions never get asked for it. */
private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

/**
 * Android makes an app that stays connected in the background show a notification. From Android 13 an app's
 * notifications start switched off, which hides that one; media controls show either way.
 */
@Composable
private fun BackgroundNotificationNote() {
    val context = LocalContext.current
    fun allowedNow() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(allowedNow()) }
    // The user may come back from Android's settings having changed it.
    LifecycleResumeEffect(Unit) {
        allowed = allowedNow()
        onPauseOrDispose { }
    }
    var askedAt by remember { mutableLongStateOf(0L) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        // Android stops asking after two refusals and answers "no" at once. Its settings page still works.
        if (!granted && SystemClock.elapsedRealtime() - askedAt < 400) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    if (allowed) {
        Help("Android shows a silent notification for this. To hide it, long-press it and turn it off; the controls keep working.")
        return
    }
    Help("Notifications are off for RomCon. If the controls don't appear, allow them.")
    TextButton(
        onClick = {
            askedAt = SystemClock.elapsedRealtime()
            ask.launch(POST_NOTIFICATIONS)
        },
    ) { Text("Allow notifications") }
}

@Composable
private fun ConnectionSettings(vm: AppViewModel, s: AppSettings) {
    val conn by vm.conn.collectAsStateWithLifecycle()

    Section("Pointer movement and scrolling")
    Column(Modifier.selectableGroup()) {
        RadioSetting(
            "TCP",
            "Reliable, but the pointer can stutter on weak Wi-Fi",
            selected = s.transport == Transport.TCP,
        ) { vm.updateSettings { it.copy(transport = Transport.TCP) } }
        RadioSetting(
            "UDP",
            "Smoother on weak Wi-Fi, but a little movement can get lost",
            selected = s.transport == Transport.UDP,
        ) { vm.updateSettings { it.copy(transport = Transport.UDP) } }
    }
    Help("Clicks and typing always use TCP. Everything is encrypted.")

    if (s.transport == Transport.UDP) {
        Section("Status")
        val connected = conn as? ConnState.Connected
        Help(
            when {
                connected == null -> "Starts when you connect. If UDP can't get through, TCP is used."
                connected.udp -> "Using UDP with ${connected.pc.name}."
                else -> "Using TCP until ${connected.pc.name} answers over UDP. If it never does, update RomCon on the PC " +
                    "or allow UDP port ${connected.pc.port} in Windows Firewall."
            },
        )
    }
}

@Composable
private fun GeneralSettings(vm: AppViewModel, s: AppSettings) {
    SwitchSetting("Vibrate on clicks and keys", null, s.haptics) { v -> vm.updateSettings { it.copy(haptics = v) } }
    SwitchSetting("Keep screen on", null, s.keepScreenOn) { v -> vm.updateSettings { it.copy(keepScreenOn = v) } }
    SwitchSetting("Lock PC button", null, s.lockButton) { v -> vm.updateSettings { it.copy(lockButton = v) } }
}

@Composable
private fun PageLink(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A list action, like New shortcut: its icon and text line up with the icons and titles of the rows around it. */
@Composable
internal fun ActionRow(icon: ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun RadioSetting(title: String, summary: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SliderSetting(title: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(String.format(Locale.ROOT, format, value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchSetting(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = MaterialTheme.typography.bodyMedium.lineHeight)
}
