package dev.romcon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
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
import androidx.compose.material3.TextButton
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
import dev.romcon.data.BarButton
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
                    SettingsPage.KEYBOARD -> KeyboardSettings(vm, s)
                    SettingsPage.BOTTOM_BAR -> BottomBarSettings(vm, s)
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
    PageLink(Icons.Filled.TouchApp, "Bottom bar", s.bottomBar.joinToString(", ") { it.title }) { vm.openSettingsPage(SettingsPage.BOTTOM_BAR) }
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
    if (!s.tapToClick && BarButton.LEFT_CLICK !in s.bottomBar) {
        Help("With tap to click off there is nothing to click with. Add Left click under Settings, Bottom bar.")
    }

    Section("Scrolling")
    SliderSetting("Scroll speed", s.scrollSpeed, 0.3f..3f, "%.1f×") { v -> vm.updateSettings { it.copy(scrollSpeed = v) } }
    SwitchSetting("Natural scrolling", "Content follows your fingers, like on the phone", s.naturalScroll) { v ->
        vm.updateSettings { it.copy(naturalScroll = v) }
    }
    SwitchSetting("Smooth scrolling", "Turn off if an older app scrolls too little or not at all", s.smoothScroll) { v ->
        vm.updateSettings { it.copy(smoothScroll = v) }
    }
    SwitchSetting("Momentum", "Keep scrolling briefly after a quick swipe", s.scrollMomentum) { v ->
        vm.updateSettings { it.copy(scrollMomentum = v) }
    }

    Section("Scroll bar")
    SwitchSetting("Show the scroll bar", "A strip down the right side of the trackpad: slide one finger along it to scroll", s.scrollBar) { v ->
        vm.updateSettings { it.copy(scrollBar = v) }
    }
    if (s.scrollBar) {
        SwitchSetting(
            "Reverse the scroll bar",
            if (s.scrollBarReversed) "Sliding down moves up the page" else "Sliding down moves down the page",
            s.scrollBarReversed,
        ) { v -> vm.updateSettings { it.copy(scrollBarReversed = v) } }
    }

    Section("Gestures")
    Help(
        "One finger: move the pointer\n" +
            "Tap: left click · double-tap: double click\n" +
            "Double-tap and drag: drag (select text, move windows)\n" +
            "Two fingers: scroll · two-finger tap: right click\n" +
            "Three-finger tap: middle click\n" +
            "Scroll bar: slide one finger up or down to scroll",
    )
}

@Composable
private fun KeyboardSettings(vm: AppViewModel, s: AppSettings) {
    Section("Modifier keys")
    Help(
        "Open Shortcuts or Keys from the bottom bar, then tap Ctrl, Alt, Shift or Win to apply it to the next key.\n" +
            "Long-press one to lock it on until you tap it again.\n" +
            "With a modifier on, letters typed on the phone keyboard become shortcuts: Ctrl, then C, copies.",
    )

    Section("Typing")
    SwitchSetting(
        "Show what you type",
        "A box above the phone keyboard repeats what you type on the PC, so you can spot a typo without looking up",
        s.typingPreview,
    ) { v -> vm.updateSettings { it.copy(typingPreview = v) } }
    if (s.typingPreview) {
        Help(
            "The box only knows what this phone typed. It starts again when you press Enter, click, or use a shortcut, " +
                "because the caret on the PC may have moved.",
        )
    }
    Help(
        "Suggestions and autocorrect stay off while typing to the PC, since the phone can't see what's on its screen.\n" +
            "Use Send text to write a longer passage on the phone and send it in one go.",
    )
}

@Composable
private fun BottomBarSettings(vm: AppViewModel, s: AppSettings) {
    val bar = s.bottomBar
    val setBar: (List<BarButton>) -> Unit = { buttons -> vm.updateSettings { it.copy(bottomBar = buttons) } }
    val full = bar.size >= BarButton.MAX_ON_BAR

    Help("The buttons under the trackpad, from left to right. Up to ${BarButton.MAX_ON_BAR} fit.")

    Section("On the bar")
    bar.forEachIndexed { i, button ->
        // Keyed, so the row keeps its identity (and its focus) as it moves up or down.
        key(button) {
            BarButtonRow(button) {
                IconButton(onClick = { setBar(bar.swapped(i, i - 1)) }, enabled = i > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${button.title} left")
                }
                IconButton(onClick = { setBar(bar.swapped(i, i + 1)) }, enabled = i < bar.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${button.title} right")
                }
                // The bar always keeps one button, so there's never an empty strip.
                IconButton(onClick = { setBar(bar - button) }, enabled = bar.size > 1) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove ${button.title}")
                }
            }
        }
    }

    Section("More buttons")
    if (full) Help("The bar is full. Remove a button to add another.")
    BarButton.entries.filterNot { it in bar }.forEach { button ->
        key(button) {
            BarButtonRow(button) {
                IconButton(onClick = { setBar(bar + button) }, enabled = !full) {
                    Icon(Icons.Filled.Add, contentDescription = "Add ${button.title}")
                }
            }
        }
    }

    TextButton(onClick = { setBar(BarButton.DEFAULT) }, enabled = bar != BarButton.DEFAULT, modifier = Modifier.padding(top = 12.dp)) {
        Text("Reset to Keyboard, Windows key, Shortcuts")
    }
}

private fun <T> List<T>.swapped(a: Int, b: Int): List<T> = toMutableList().also {
    val moved = it[a]
    it[a] = it[b]
    it[b] = moved
}

@Composable
private fun BarButtonRow(button: BarButton, controls: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(button.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(button.title, style = MaterialTheme.typography.bodyLarge)
            Text(button.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        controls()
    }
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
