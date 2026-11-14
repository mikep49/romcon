package dev.romcon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.romcon.AppViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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

            Section("General")
            SwitchSetting("Vibrate on clicks", null, s.haptics) { v -> vm.updateSettings { it.copy(haptics = v) } }
            SwitchSetting("Keep screen on", "While controlling a PC", s.keepScreenOn) { v -> vm.updateSettings { it.copy(keepScreenOn = v) } }

            Section("Gestures")
            Help(
                "One finger: move the pointer\n" +
                    "Tap: left click · double-tap: double click\n" +
                    "Double-tap and drag: drag (select text, move windows)\n" +
                    "Two fingers: scroll · two-finger tap: right click\n" +
                    "Three-finger tap: middle click\n" +
                    "Hold Left/Right below the pad and move with another finger to drag\n" +
                    "Keys panel: tap Ctrl/Alt/Shift/Win for the next key, long-press to lock",
            )

            Section("About")
            Help("RomCon $version\nThe PC sees this phone as \"${vm.deviceName}\".")
            Spacer(Modifier.padding(bottom = 24.dp))
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
