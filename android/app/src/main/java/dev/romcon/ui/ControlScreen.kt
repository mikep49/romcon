package dev.romcon.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.romcon.AppViewModel
import dev.romcon.ConnState
import dev.romcon.data.BarButton
import dev.romcon.data.CustomShortcut
import dev.romcon.input.Latch
import dev.romcon.input.ModKey
import dev.romcon.input.Vk
import dev.romcon.net.FailKind
import dev.romcon.net.Proto

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(vm: AppViewModel) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val view = LocalView.current
    val keyboard = remember { KeyboardController() }
    var keysOpen by rememberSaveable { mutableStateOf(false) }
    var keysTab by rememberSaveable { mutableIntStateOf(0) }
    var textDialog by rememberSaveable { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    val hapticsOn by rememberUpdatedState(settings.haptics)

    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val haptic: (Boolean) -> Unit = remember(view) {
        { strong ->
            if (hapticsOn) {
                view.performHapticFeedback(if (strong) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }
    val actions = remember(vm) {
        object : PointerActions {
            override fun move(dx: Int, dy: Int) {
                vm.move(dx, dy)
            }

            override fun scroll(vertical: Int, horizontal: Int) {
                vm.scroll(vertical, horizontal)
            }

            override fun button(button: Int, down: Boolean) {
                vm.button(button, down)
            }

            override fun click(button: Int) = vm.click(button)

            override fun haptic(strong: Boolean) = haptic(strong)
        }
    }
    val sink = remember(vm) {
        object : KeySink {
            override fun onText(text: String) = vm.type(text)

            override fun onKey(vk: Int, mods: Int) = vm.key(vk, mods)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ControlTopBar(conn, vm, lockButton = settings.lockButton, onSendText = { textDialog = true })

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            Trackpad(settings, actions, Modifier.fillMaxSize())
            ConnectionOverlay(conn, vm, Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(visible = imeVisible && settings.typingPreview, enter = expandVertically(), exit = shrinkVertically()) {
            TypingPreview(vm.typed, vm::clearTyped, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
        }

        AnimatedVisibility(visible = keysOpen, enter = expandVertically(), exit = shrinkVertically()) {
            KeysPanel(vm, settings.shortcuts, haptic, keysTab, { keysTab = it }, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
        }

        // Shortcuts and Keys open the same panel on different tabs; each is lit while its side of the panel shows.
        val onShortcuts = keysOpen && keysTab == SHORTCUTS_TAB
        val onKeys = keysOpen && keysTab != SHORTCUTS_TAB
        BottomBar(
            items = settings.bottomBar,
            shortcuts = settings.shortcuts,
            running = vm.runningShortcut,
            selected = { button ->
                when (button) {
                    BarButton.KEYBOARD -> imeVisible
                    BarButton.SHORTCUTS -> onShortcuts
                    BarButton.KEYS -> onKeys
                    else -> false
                }
            },
            onPress = { button ->
                when (button) {
                    BarButton.KEYBOARD -> if (imeVisible) keyboard.hide() else keyboard.show()
                    BarButton.SHORTCUTS -> if (onShortcuts) {
                        keysOpen = false
                    } else {
                        keysTab = SHORTCUTS_TAB
                        keysOpen = true
                    }
                    BarButton.KEYS -> if (onKeys) {
                        keysOpen = false
                    } else {
                        if (keysTab == SHORTCUTS_TAB) keysTab = 0
                        keysOpen = true
                    }
                    BarButton.SEND_TEXT -> textDialog = true
                    else -> {
                        haptic(false)
                        vm.press(button)
                    }
                }
            },
            onShortcut = { shortcut ->
                haptic(false)
                vm.runShortcut(shortcut)
            },
            onMouse = { button, down ->
                if (down) haptic(false)
                actions.button(button, down)
            },
        )

        KeyboardCapture(keyboard, sink, hideOnEnter = settings.hideKeyboardOnEnter)
    }

    if (textDialog) {
        SendTextDialog(
            onSend = {
                vm.sendText(it)
                textDialog = false
            },
            onDismiss = { textDialog = false },
        )
    }
}

@Composable
private fun ControlTopBar(conn: ConnState, vm: AppViewModel, lockButton: Boolean, onSendText: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val (name, status, color) = when (conn) {
        is ConnState.Connected -> Triple(
            conn.pc.name,
            when {
                conn.pcPaused -> "Paused on the PC"
                conn.udp -> "Connected · UDP"
                else -> "Connected"
            },
            if (conn.pcPaused) Color(0xFFE0A100) else Color(0xFF22A55B),
        )
        is ConnState.Connecting -> Triple(conn.pc.name, if (conn.attempt == 0) "Connecting…" else "Reconnecting…", Color(0xFFE0A100))
        is ConnState.Failed -> Triple(conn.pc.name, "Not connected", MaterialTheme.colorScheme.error)
        ConnState.Idle -> Triple("RomCon", "Not connected", MaterialTheme.colorScheme.outline)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = vm::disconnect) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Disconnect") }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (lockButton) {
            IconButton(onClick = { vm.lockPc() }, enabled = conn is ConnState.Connected) {
                Icon(Icons.Filled.Lock, contentDescription = "Lock the PC")
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Send text…") }, onClick = { menu = false; onSendText() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; vm.openSettings() })
                DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; vm.disconnect() })
            }
        }
    }
}

@Composable
private fun ConnectionOverlay(conn: ConnState, vm: AppViewModel, modifier: Modifier) {
    when (conn) {
        is ConnState.Connecting -> OverlayCard(modifier) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(
                if (conn.attempt == 0) "Connecting to ${conn.pc.name}…" else "Reconnecting to ${conn.pc.name}…",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            conn.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (conn.attempt > 0) TextButton(onClick = vm::retryNow) { Text("Try now") }
        }
        is ConnState.Failed -> OverlayCard(modifier) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(conn.message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            when (conn.kind) {
                FailKind.NOT_PAIRED -> Button(onClick = { vm.repair(conn.pc, trustNewIdentity = false) }) { Text("Pair again") }
                FailKind.IDENTITY_CHANGED -> Button(onClick = { vm.repair(conn.pc, trustNewIdentity = true) }) { Text("Pair with the new identity") }
                else -> Unit
            }
            TextButton(onClick = vm::disconnect) { Text("Back to PCs") }
        }
        else -> Unit
    }
}

@Composable
private fun OverlayCard(modifier: Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.padding(24.dp).widthIn(max = 360.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

internal suspend fun AwaitPointerEventScope.waitForRelease() {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) return
    }
}

// ---------- Keys panel ----------

private data class Shortcut(val label: String, val vk: Int, val mods: Int)

private const val CTRL = Proto.MOD_CTRL
private const val ALT = Proto.MOD_ALT
private const val SHIFT = Proto.MOD_SHIFT
private const val WIN = Proto.MOD_WIN

private val shortcuts = listOf(
    Shortcut("Copy", Vk.letter('C'), CTRL),
    Shortcut("Cut", Vk.letter('X'), CTRL),
    Shortcut("Paste", Vk.letter('V'), CTRL),
    Shortcut("Undo", Vk.letter('Z'), CTRL),
    Shortcut("Redo", Vk.letter('Y'), CTRL),
    Shortcut("Select all", Vk.letter('A'), CTRL),
    Shortcut("Find", Vk.letter('F'), CTRL),
    Shortcut("Save", Vk.letter('S'), CTRL),
    Shortcut("New tab", Vk.letter('T'), CTRL),
    Shortcut("Close tab", Vk.letter('W'), CTRL),
    Shortcut("Reopen tab", Vk.letter('T'), CTRL or SHIFT),
    Shortcut("Refresh", Vk.f(5), 0),
    Shortcut("Zoom in", Vk.OEM_PLUS, CTRL),
    Shortcut("Zoom out", Vk.OEM_MINUS, CTRL),
    Shortcut("Task view", Vk.TAB, WIN),
    Shortcut("Desktop", Vk.letter('D'), WIN),
    Shortcut("Explorer", Vk.letter('E'), WIN),
    Shortcut("Run", Vk.letter('R'), WIN),
    Shortcut("Snip", Vk.letter('S'), WIN or SHIFT),
    Shortcut("Task Mgr", Vk.ESC, CTRL or SHIFT),
    Shortcut("Close app", Vk.f(4), ALT),
)

private val keysTabs = listOf("Keys", "F1–F12", "Media", "Shortcuts")
private const val SHORTCUTS_TAB = 3

/** Bottom-bar buttons that press a key or a shortcut on the PC; the rest are handled by the screen itself. */
private fun AppViewModel.press(button: BarButton) {
    when (button) {
        BarButton.WINDOWS -> key(Vk.WIN)
        BarButton.SWITCH_APP -> switchApp()
        BarButton.TASK_VIEW -> shortcut(Vk.TAB, WIN)
        BarButton.DESKTOP -> shortcut(Vk.letter('D'), WIN)
        BarButton.ESC -> key(Vk.ESC)
        BarButton.ENTER -> key(Vk.ENTER)
        BarButton.COPY -> shortcut(Vk.letter('C'), CTRL)
        BarButton.PASTE -> shortcut(Vk.letter('V'), CTRL)
        BarButton.PLAY_PAUSE -> key(Vk.MEDIA_PLAY_PAUSE)
        BarButton.VOLUME_DOWN -> key(Vk.VOLUME_DOWN)
        BarButton.VOLUME_UP -> key(Vk.VOLUME_UP)
        BarButton.MUTE -> key(Vk.VOLUME_MUTE)
        else -> Unit
    }
}

/** A key in the Shortcuts tab. [on] lights it up. */
private class Cap(val label: String, val on: Boolean = false, val action: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeysPanel(
    vm: AppViewModel,
    custom: List<CustomShortcut>,
    haptic: (Boolean) -> Unit,
    tab: Int,
    onTab: (Int) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModKey.entries.forEach { mod ->
                val latch = vm.latches[mod] ?: Latch.OFF
                val bg = when (latch) {
                    Latch.OFF -> MaterialTheme.colorScheme.surfaceContainerHighest
                    Latch.ONCE -> MaterialTheme.colorScheme.primaryContainer
                    Latch.LOCKED -> MaterialTheme.colorScheme.primary
                }
                val fg = when (latch) {
                    Latch.OFF -> MaterialTheme.colorScheme.onSurface
                    Latch.ONCE -> MaterialTheme.colorScheme.onPrimaryContainer
                    Latch.LOCKED -> MaterialTheme.colorScheme.onPrimary
                }
                Surface(
                    color = bg,
                    contentColor = fg,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(
                            onClick = { haptic(false); vm.toggleLatch(mod) },
                            onLongClick = { haptic(true); vm.lockLatch(mod) },
                        ),
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(mod.label, style = MaterialTheme.typography.labelLarge, fontWeight = if (latch == Latch.OFF) FontWeight.Normal else FontWeight.Bold)
                        if (latch == Latch.LOCKED) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.Lock, contentDescription = "locked", modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            keysTabs.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = tab == i,
                    onClick = { onTab(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, keysTabs.size),
                    icon = {},
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        // A fixed height, so switching tabs never moves the rows above under the user's finger. Four rows fit with a
        // little to spare for rounding; a tab with more scrolls, and its edges fade to show there's more.
        val scroll = rememberScrollState()
        Column(
            Modifier
                .height(KEYS_HEIGHT)
                .fadingEdges(scroll)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val key: (Int) -> () -> Unit = { vk -> { haptic(false); vm.key(vk) } }
            when (tab) {
                0 -> {
                    KeyRow {
                        KeyCap("Esc", it, onPress = key(Vk.ESC))
                        KeyCap("Tab", it, onPress = key(Vk.TAB))
                        KeyCap("Win", it, onPress = key(Vk.WIN))
                        KeyCap("Menu", it, onPress = key(Vk.APPS))
                        KeyCap("PrtSc", it, onPress = key(Vk.PRINT_SCREEN))
                    }
                    KeyRow {
                        KeyCap("Ins", it, onPress = key(Vk.INSERT))
                        KeyCap("Home", it, onPress = key(Vk.HOME))
                        KeyCap("End", it, onPress = key(Vk.END))
                        KeyCap("PgUp", it, repeat = true, onPress = key(Vk.PAGE_UP))
                        KeyCap("PgDn", it, repeat = true, onPress = key(Vk.PAGE_DOWN))
                    }
                    KeyRow {
                        KeyCap("Backspace", it, repeat = true, icon = Icons.AutoMirrored.Outlined.Backspace, onPress = key(Vk.BACK))
                        KeyCap("Del", it, repeat = true, onPress = key(Vk.DELETE))
                        KeyCap("Space", it, repeat = true, onPress = key(Vk.SPACE))
                        KeyCap("Enter", it, onPress = key(Vk.ENTER))
                    }
                    // Arrow keys point the same way in every language, so these turn one upward arrow rather than mirror.
                    KeyRow {
                        KeyCap("Left arrow", it, repeat = true, icon = Icons.Filled.ArrowUpward, turn = -90f, onPress = key(Vk.LEFT))
                        KeyCap("Up arrow", it, repeat = true, icon = Icons.Filled.ArrowUpward, onPress = key(Vk.UP))
                        KeyCap("Down arrow", it, repeat = true, icon = Icons.Filled.ArrowUpward, turn = 180f, onPress = key(Vk.DOWN))
                        KeyCap("Right arrow", it, repeat = true, icon = Icons.Filled.ArrowUpward, turn = 90f, onPress = key(Vk.RIGHT))
                    }
                }
                1 -> (1..12).chunked(6).forEach { row ->
                    KeyRow { m -> row.forEach { n -> KeyCap("F$n", m, onPress = key(Vk.f(n))) } }
                }
                2 -> {
                    KeyRow {
                        KeyCap("Previous track", it, icon = Icons.Filled.SkipPrevious, onPress = key(Vk.MEDIA_PREV))
                        KeyCap("Play or pause", it, icon = Icons.Filled.PlayArrow, onPress = key(Vk.MEDIA_PLAY_PAUSE))
                        KeyCap("Next track", it, icon = Icons.Filled.SkipNext, onPress = key(Vk.MEDIA_NEXT))
                    }
                    KeyRow {
                        KeyCap("Volume down", it, repeat = true, icon = Icons.AutoMirrored.Filled.VolumeDown, onPress = key(Vk.VOLUME_DOWN))
                        KeyCap("Mute", it, icon = Icons.AutoMirrored.Filled.VolumeOff, onPress = key(Vk.VOLUME_MUTE))
                        KeyCap("Volume up", it, repeat = true, icon = Icons.AutoMirrored.Filled.VolumeUp, onPress = key(Vk.VOLUME_UP))
                    }
                }
                else -> {
                    // The user's own shortcuts come first, so ones that don't fit on the bar are still close at hand.
                    // One that's partway through its hotkeys is lit, and pressing it again stops it.
                    val all = custom.map { sc -> Cap(sc.title, on = sc.id == vm.runningShortcut) { haptic(false); vm.runShortcut(sc) } } +
                        Cap("Switch app") { haptic(false); vm.switchApp() } +
                        shortcuts.map { sc -> Cap(sc.label) { haptic(false); vm.shortcut(sc.vk, sc.mods) } }
                    all.chunked(4).forEach { row ->
                        KeyRow { m ->
                            row.forEach { cap -> KeyCap(cap.label, m, on = cap.on, onPress = cap.action) }
                            repeat(4 - row.size) { Spacer(m) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyRow(content: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        content(Modifier.weight(1f))
    }
}

/** Four rows of keys and the gaps between them, plus a couple of dp so rounding to whole pixels never clips the last row. */
private val KEYS_HEIGHT = 196.dp

/** Fades out whatever is scrolled past the top or bottom edge, so a list that continues looks like it does. */
private fun Modifier.fadingEdges(scroll: ScrollState, length: Dp = 20.dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val h = length.toPx().coerceAtMost(size.height / 2)
        if (scroll.canScrollBackward) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = 0f, endY = h),
                size = Size(size.width, h),
                blendMode = BlendMode.DstOut,
            )
        }
        if (scroll.canScrollForward) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = size.height - h, endY = size.height),
                topLeft = Offset(0f, size.height - h),
                size = Size(size.width, h),
                blendMode = BlendMode.DstOut,
            )
        }
    }

/**
 * One key. With an [icon] the cap shows just that, and [label] is what screen readers say; [turn] rotates the icon,
 * in degrees. With [repeat], holding it presses again and again.
 */
@Composable
private fun KeyCap(
    label: String,
    modifier: Modifier,
    repeat: Boolean = false,
    icon: ImageVector? = null,
    turn: Float = 0f,
    on: Boolean = false,
    onPress: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val idle = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val text: @Composable () -> Unit = {
        Box(Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp).rotate(turn))
            } else {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (!repeat) {
        Surface(
            onClick = onPress,
            shape = shape,
            color = idle,
            modifier = modifier.height(44.dp).semantics { if (on) stateDescription = "Running" },
        ) { text() }
        return
    }
    // Held keys repeat, like on a real keyboard.
    var pressed by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(onPress)
    Surface(
        shape = shape,
        color = if (pressed) MaterialTheme.colorScheme.primaryContainer else idle,
        modifier = modifier
            .height(44.dp)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                onClick {
                    current()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    current()
                    try {
                        var wait = 420L
                        while (withTimeoutOrNull(wait) { waitForRelease() } == null) {
                            current()
                            wait = 45L
                        }
                    } finally {
                        pressed = false
                    }
                }
            },
    ) { text() }
}

@Composable
private fun SendTextDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var enter by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Typed on the PC wherever the cursor is. Good for links, long text, or pasting from the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                // The whole row toggles, so the box itself can sit flush with the text field's edge.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .toggleable(value = enter, role = Role.Checkbox, onValueChange = { enter = it })
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = enter, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Press Enter afterwards")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(if (enter) text + "\n" else text) }, enabled = text.isNotEmpty()) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
