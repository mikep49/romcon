package dev.romcon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.romcon.data.BarButton
import dev.romcon.data.BarItem
import dev.romcon.data.CustomShortcut
import dev.romcon.net.Proto

val BarButton.icon: ImageVector
    get() = when (this) {
        BarButton.KEYBOARD -> Icons.Filled.Keyboard
        BarButton.WINDOWS -> Icons.Filled.Window
        BarButton.SHORTCUTS -> Icons.Filled.Bolt
        BarButton.KEYS -> Icons.Filled.Dialpad
        BarButton.SEND_TEXT -> Icons.Filled.TextFields
        BarButton.SWITCH_APP -> Icons.Filled.SwapHoriz
        BarButton.TASK_VIEW -> Icons.Filled.Layers
        BarButton.DESKTOP -> Icons.Filled.DesktopWindows
        BarButton.ESC -> Icons.Filled.Close
        BarButton.ENTER -> Icons.AutoMirrored.Filled.KeyboardReturn
        BarButton.COPY -> Icons.Filled.ContentCopy
        BarButton.PASTE -> Icons.Filled.ContentPaste
        BarButton.PLAY_PAUSE -> Icons.Filled.PlayArrow
        BarButton.VOLUME_DOWN -> Icons.AutoMirrored.Filled.VolumeDown
        BarButton.VOLUME_UP -> Icons.AutoMirrored.Filled.VolumeUp
        BarButton.MUTE -> Icons.AutoMirrored.Filled.VolumeOff
        BarButton.LEFT_CLICK, BarButton.RIGHT_CLICK, BarButton.MIDDLE_CLICK -> Icons.Filled.Mouse
    }

/** The mouse button a bar button holds down while it's touched, if it is one. */
private val BarButton.mouseButton: Int?
    get() = when (this) {
        BarButton.LEFT_CLICK -> Proto.BUTTON_LEFT
        BarButton.RIGHT_CLICK -> Proto.BUTTON_RIGHT
        BarButton.MIDDLE_CLICK -> Proto.BUTTON_MIDDLE
        else -> null
    }

/** Held down, these fire again and again, like a held key. */
private val BarButton.repeats: Boolean
    get() = this == BarButton.VOLUME_DOWN || this == BarButton.VOLUME_UP

/**
 * The row of buttons under the trackpad; which ones, and in what order, is chosen in Settings.
 * [onPress] fires for a tap (and for each repeat of a held volume button); [onMouse] mirrors the finger on a mouse button.
 * [onShortcut] fires for a tap on one of the user's own shortcuts, looked up in [shortcuts].
 */
@Composable
fun BottomBar(
    items: List<BarItem>,
    shortcuts: List<CustomShortcut>,
    selected: (BarButton) -> Boolean,
    onPress: (BarButton) -> Unit,
    onShortcut: (CustomShortcut) -> Unit,
    onMouse: (button: Int, down: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        items.forEach { item ->
            // Keyed, so a finger resting on one button isn't handed to another when the bar is rearranged.
            key(item) {
                val slot = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                when (item) {
                    is BarItem.Builtin -> BuiltinBarItem(item.button, slot, selected(item.button), onPress, onMouse)
                    is BarItem.Custom -> shortcuts.firstOrNull { it.id == item.id }?.let { shortcut ->
                        BarCell(
                            label = shortcut.title,
                            description = shortcut.title,
                            highlighted = false,
                            modifier = slot.clickable(role = Role.Button) { onShortcut(shortcut) },
                        ) { color -> ShortcutGlyph(shortcut, color) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltinBarItem(
    button: BarButton,
    modifier: Modifier,
    on: Boolean,
    onPress: (BarButton) -> Unit,
    onMouse: (button: Int, down: Boolean) -> Unit,
) {
    val mouse = button.mouseButton
    when {
        mouse != null -> HeldBarItem(button, modifier, repeat = false) { down -> onMouse(mouse, down) }
        button.repeats -> HeldBarItem(button, modifier, repeat = true) { down -> if (down) onPress(button) }
        else -> BarCell(
            button = button,
            icon = if (button == BarButton.KEYBOARD && on) Icons.Filled.KeyboardHide else button.icon,
            highlighted = on,
            modifier = modifier.clickable(role = Role.Button) { onPress(button) },
        )
    }
}

@Composable
private fun BarCell(button: BarButton, icon: ImageVector, highlighted: Boolean, modifier: Modifier) {
    // Screen readers get the full name: "Volume down" rather than "Vol −".
    BarCell(button.label, button.title, highlighted, modifier) { color -> Icon(icon, contentDescription = null, tint = color) }
}

@Composable
private fun BarCell(label: String, description: String, highlighted: Boolean, modifier: Modifier, icon: @Composable (Color) -> Unit) {
    val color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.padding(horizontal = 2.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon(color)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

/**
 * A button that follows the finger. A mouse button stays pressed until the finger lifts, so you can hold it and
 * drag on the trackpad with another finger. With [repeat], [onChange] gets `true` again and again while held.
 */
@Composable
private fun HeldBarItem(button: BarButton, modifier: Modifier, repeat: Boolean, onChange: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(onChange)
    BarCell(
        button = button,
        icon = button.icon,
        highlighted = pressed,
        modifier = modifier
            .background(if (pressed) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick {
                    current(true)
                    current(false)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    current(true)
                    try {
                        if (repeat) {
                            var wait = 420L
                            while (withTimeoutOrNull(wait) { waitForRelease() } == null) {
                                current(true)
                                wait = 90L
                            }
                        } else {
                            waitForRelease()
                        }
                    } finally {
                        pressed = false
                        current(false)
                    }
                }
            },
    )
}
