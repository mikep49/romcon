package dev.romcon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.romcon.AppViewModel
import dev.romcon.SettingsPage
import dev.romcon.data.AppSettings
import dev.romcon.data.ThemeMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val page = vm.settingsPage

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.title ?: "Settings") },
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
                    SettingsPage.KEYBOARD -> KeyboardSettings()
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
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    PageLink(Icons.Filled.Palette, "Appearance", "Theme: ${s.theme.label}") { vm.openSettingsPage(SettingsPage.APPEARANCE) }
    PageLink(Icons.Filled.Mouse, "Mouse", "Pointer speed, scrolling, tap to click") { vm.openSettingsPage(SettingsPage.MOUSE) }
    PageLink(Icons.Filled.Keyboard, "Keyboard", "Modifier keys and typing") { vm.openSettingsPage(SettingsPage.KEYBOARD) }
    PageLink(Icons.Filled.Tune, "General", "Vibration, keep screen on") { vm.openSettingsPage(SettingsPage.GENERAL) }

    Section("About")
    Help("RomCon $version\nThe PC sees this phone as \"${vm.deviceName}\".")
}

@Composable
private fun AppearanceSettings(vm: AppViewModel, s: AppSettings) {
    Section("Theme")
    Column(Modifier.selectableGroup()) {
        ThemeMode.entries.forEach { mode ->
            val summary = when (mode) {
                ThemeMode.SYSTEM -> "Follow the phone's light or dark setting"
                ThemeMode.LIGHT, ThemeMode.DARK -> null
                ThemeMode.OLED -> "Pure black background, easy on OLED screens and the battery"
            }
            RadioSetting(mode.label, summary, selected = s.theme == mode) { vm.updateSettings { it.copy(theme = mode) } }
        }
    }
}

@Composable
private fun MouseSettings(vm: AppViewModel, s: AppSettings) {
    Section("Pointer")
    SliderSetting("Pointer speed", s.pointerSpeed, 0.3f..3f, "%.1f×") { v -> vm.updateSettings { it.copy(pointerSpeed = v) } }
    SliderSetting("Acceleration", s.acceleration, 0f..1.5f, "%.1f") { v -> vm.updateSettings { it.copy(acceleration = v) } }
    SwitchSetting("Tap to click", "Tap to left-click, two-finger tap to right-click, three-finger tap to middle-click", s.tapToClick) { v ->
        vm.updateSettings { it.copy(tapToClick = v) }
    }

    Section("Scrolling")
    SliderSetting("Scroll speed", s.scrollSpeed, 0.3f..3f, "%.1f×") { v -> vm.updateSettings { it.copy(scrollSpeed = v) } }
    SwitchSetting("Natural scrolling", "Content follows your fingers, like on the phone", s.naturalScroll) { v ->
        vm.updateSettings { it.copy(naturalScroll = v) }
    }
    SwitchSetting("Smooth scrolling", "Turn off if an older app scrolls too little or not at all", s.smoothScroll) { v ->
        vm.updateSettings { it.copy(smoothScroll = v) }
    }
    SwitchSetting("Momentum", "Keep scrolling briefly after a quick two-finger swipe", s.scrollMomentum) { v ->
        vm.updateSettings { it.copy(scrollMomentum = v) }
    }

    Section("Gestures")
    Help(
        "One finger: move the pointer\n" +
            "Tap: left click · double-tap: double click\n" +
            "Double-tap and drag: drag (select text, move windows)\n" +
            "Two fingers: scroll · two-finger tap: right click\n" +
            "Three-finger tap: middle click\n" +
            "Hold Left/Right below the pad and move with another finger to drag",
    )
}

@Composable
private fun KeyboardSettings() {
    Section("Modifier keys")
    Help(
        "In the Keys panel, tap Ctrl, Alt, Shift or Win to apply it to the next key.\n" +
            "Long-press one to lock it on until you tap it again.\n" +
            "With a modifier on, letters typed on the phone keyboard become shortcuts: Ctrl, then C, copies.",
    )

    Section("Typing")
    Help(
        "Suggestions and autocorrect stay off while typing to the PC, since the phone can't see what's on its screen.\n" +
            "Use Send text to write a longer passage on the phone and send it in one go.",
    )
}

@Composable
private fun GeneralSettings(vm: AppViewModel, s: AppSettings) {
    Section("Feedback")
    SwitchSetting("Vibrate on clicks", "Also on key presses", s.haptics) { v -> vm.updateSettings { it.copy(haptics = v) } }

    Section("Screen")
    SwitchSetting("Keep screen on", "While controlling a PC", s.keepScreenOn) { v -> vm.updateSettings { it.copy(keepScreenOn = v) } }
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
private fun Section(title: String) {
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
private fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = MaterialTheme.typography.bodyMedium.lineHeight)
}
