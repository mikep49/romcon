package dev.romcon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import dev.romcon.data.AppSettings
import dev.romcon.net.Proto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

interface PointerActions {
    fun move(dx: Int, dy: Int)
    fun scroll(vertical: Int, horizontal: Int)
    fun button(button: Int, down: Boolean)
    fun click(button: Int)
    fun haptic(strong: Boolean)
}

private const val TAP_MAX_MS = 250L
private const val DOUBLE_TAP_MS = 280L

/** PC pixels per dp of finger travel at pointer speed 1.0, before acceleration. */
private const val BASE_GAIN = 1.6f

/** Wheel units per dp of finger travel, two fingers on the pad or one on the scroll bar; Windows treats 120 units as one notch. */
private const val SCROLL_GAIN = 3f

/** Momentum scrolling never starts faster than this, in dp per second. */
private const val MAX_FLING = 4000f

/** With natural scrolling the content follows the fingers, like scrolling a page on the phone. */
private val AppSettings.padDirection: Float get() = if (naturalScroll) 1f else -1f

/** The scroll bar works like one on the PC: drag down to move down the page, unless reversed in Settings. */
private val AppSettings.barDirection: Float get() = if (scrollBarReversed) 1f else -1f

private enum class Axis { FREE, VERTICAL, HORIZONTAL }

/** Turns finger travel (in dp) into whole PC pixels and wheel units, carrying fractions over between events. */
private class TrackpadMotion {
    var remX = 0f
    var remY = 0f
    var remV = 0f
    var remH = 0f
    var lastTapUp = 0L
    var lastTapPos = Offset.Zero
    var fling: Job? = null

    fun pointer(dxDp: Float, dyDp: Float, dtMs: Long, s: AppSettings, actions: PointerActions) {
        val speed = hypot(dxDp, dyDp) / dtMs.coerceAtLeast(1) // dp per ms
        val gain = BASE_GAIN * s.pointerSpeed * (1f + s.acceleration * min(speed, 3f))
        remX += dxDp * gain
        remY += dyDp * gain
        val ix = remX.toInt()
        val iy = remY.toInt()
        remX -= ix
        remY -= iy
        if (ix != 0 || iy != 0) actions.move(ix, iy)
    }

    /** [direction] is 1 when the content should follow the finger and -1 when it should move the other way. */
    fun scroll(dxDp: Float, dyDp: Float, direction: Float, s: AppSettings, actions: PointerActions) {
        val k = SCROLL_GAIN * s.scrollSpeed * direction
        remV += dyDp * k
        remH -= dxDp * k
        val step = if (s.smoothScroll) 1 else 120
        val v = (remV / step).toInt() * step
        val h = (remH / step).toInt() * step
        remV -= v
        remH -= h
        if (v != 0 || h != 0) actions.scroll(v, h)
    }

    fun resetScroll() {
        remV = 0f
        remH = 0f
    }
}

@Composable
fun Trackpad(settings: AppSettings, actions: PointerActions, modifier: Modifier = Modifier) {
    val motion = remember { TrackpadMotion() }
    val scope = rememberCoroutineScope()
    val currentSettings = rememberUpdatedState(settings)
    val currentActions = rememberUpdatedState(actions)
    val shape = RoundedCornerShape(28.dp)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
                .pointerInput(Unit) {
                    trackpadGestures(motion, scope, { currentSettings.value }, { currentActions.value })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Tap to click  ·  two fingers to scroll\nTwo-finger tap for right-click  ·  double-tap and drag to drag",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
        if (settings.scrollBar) {
            ScrollBar(motion, scope, { currentSettings.value }, { currentActions.value }, Modifier.width(44.dp).fillMaxHeight())
        }
    }
}

/** A strip as tall as the trackpad: slide one finger along it to scroll, as with the mouse wheel. */
@Composable
private fun ScrollBar(
    motion: TrackpadMotion,
    scope: CoroutineScope,
    settings: () -> AppSettings,
    actions: () -> PointerActions,
    modifier: Modifier,
) {
    var touched by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    val hint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (touched) 0.9f else 0.55f)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (touched) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
            .semantics {
                contentDescription = "Scroll bar"
                // Three wheel notches at a time; positive is up.
                customActions = listOf(
                    CustomAccessibilityAction("Scroll up") { actions().scroll(360, 0); true },
                    CustomAccessibilityAction("Scroll down") { actions().scroll(-360, 0); true },
                )
            }
            .pointerInput(Unit) { scrollBarGestures(motion, scope, settings, actions) { touched = it } }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = hint)
        Box(
            Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
                .width(4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (touched) 0.45f else 0.2f), CircleShape),
        )
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = hint)
    }
}

private suspend fun PointerInputScope.scrollBarGestures(
    motion: TrackpadMotion,
    scope: CoroutineScope,
    settings: () -> AppSettings,
    actions: () -> PointerActions,
    onTouched: (Boolean) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val s = settings()
        val a = actions()
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        motion.fling?.cancel()
        motion.resetScroll()
        onTouched(true)
        var travel = 0f
        var moving = false
        var total = 0f
        var lastTime = down.uptimeMillis
        var upTime = lastTime
        val velocity = VelocityTracker()

        try {
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
                // Only the finger that started the gesture scrolls; a second one resting on the bar changes nothing.
                val finger = event.changes.firstOrNull { it.id == down.id } ?: break
                upTime = finger.uptimeMillis
                if (!finger.pressed) break
                val dy = finger.position.y - finger.previousPosition.y
                lastTime = upTime
                if (!moving) {
                    travel += abs(dy)
                    if (travel <= slop) continue
                    moving = true
                }
                val dyDp = dy / density
                total += dyDp
                velocity.addPosition(upTime, Offset(0f, total))
                motion.scroll(0f, dyDp, s.barDirection, s, a)
            }
        } finally {
            onTouched(false)
        }

        if (moving && s.scrollMomentum) {
            val vy = velocity.calculateVelocity().y
            if (abs(vy) > 300f && (upTime - lastTime) < 80) {
                motion.fling = scope.launch {
                    fling(motion, 0f, vy.coerceIn(-MAX_FLING, MAX_FLING), AppSettings::barDirection, settings, actions)
                }
            }
        }
    }
}

private suspend fun PointerInputScope.trackpadGestures(
    motion: TrackpadMotion,
    scope: CoroutineScope,
    settings: () -> AppSettings,
    actions: () -> PointerActions,
) {
    val slop = viewConfiguration.touchSlop
    val doubleTapRadius = 48.dp.toPx()
    awaitEachGesture {
        val s = settings()
        val a = actions()
        val down = awaitFirstDown(requireUnconsumed = false)
        motion.fling?.cancel()
        motion.resetScroll()
        val downTime = down.uptimeMillis
        // Tap, then touch again and move: drag with the left button held, like a laptop touchpad.
        val dragCandidate = s.tapToClick &&
            downTime - motion.lastTapUp < DOUBLE_TAP_MS &&
            (down.position - motion.lastTapPos).getDistance() < doubleTapRadius
        var maxPointers = 1
        var travel = 0f
        var moving = false
        var dragging = false
        var scrolling = false
        var axis = Axis.FREE
        var scrollTotal = Offset.Zero
        var lastTime = downTime
        var upTime = downTime
        val velocity = VelocityTracker()

        try {
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
                upTime = event.changes.maxOf { it.uptimeMillis }
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                maxPointers = max(maxPointers, pressed)

                val tracked = event.changes.filter { it.pressed && it.previousPressed }
                if (tracked.isEmpty()) continue
                var dx = 0f
                var dy = 0f
                for (c in tracked) {
                    dx += c.position.x - c.previousPosition.x
                    dy += c.position.y - c.previousPosition.y
                }
                dx /= tracked.size
                dy /= tracked.size
                val t = tracked[0].uptimeMillis
                val dt = t - lastTime
                lastTime = t

                if (!moving) {
                    travel += hypot(dx, dy)
                    if (travel <= slop) continue
                    moving = true
                }
                val dxDp = dx / density
                val dyDp = dy / density

                if ((pressed >= 2 || scrolling) && !dragging) {
                    scrolling = true
                    scrollTotal += Offset(dxDp, dyDp)
                    if (axis == Axis.FREE && scrollTotal.getDistance() > 12f) {
                        axis = when {
                            abs(scrollTotal.y) > abs(scrollTotal.x) * 1.6f -> Axis.VERTICAL
                            abs(scrollTotal.x) > abs(scrollTotal.y) * 1.6f -> Axis.HORIZONTAL
                            else -> Axis.FREE
                        }
                    }
                    velocity.addPosition(t, scrollTotal)
                    motion.scroll(if (axis == Axis.VERTICAL) 0f else dxDp, if (axis == Axis.HORIZONTAL) 0f else dyDp, s.padDirection, s, a)
                } else if (maxPointers == 1 || dragging) {
                    if (dragCandidate && !dragging) {
                        a.button(Proto.BUTTON_LEFT, true)
                        a.haptic(true)
                        dragging = true
                    }
                    motion.pointer(dxDp, dyDp, dt, s, a)
                }
            }
        } finally {
            if (dragging) a.button(Proto.BUTTON_LEFT, false)
        }

        val quick = upTime - downTime < TAP_MAX_MS
        when {
            dragging -> Unit
            !moving && quick && s.tapToClick -> {
                when (maxPointers) {
                    1 -> {
                        a.click(Proto.BUTTON_LEFT)
                        a.haptic(false)
                        // A tap that was itself the second of a double tap doesn't start another one.
                        motion.lastTapUp = if (dragCandidate) 0L else upTime
                        motion.lastTapPos = down.position
                    }
                    2 -> {
                        a.click(Proto.BUTTON_RIGHT)
                        a.haptic(true)
                    }
                    else -> {
                        a.click(Proto.BUTTON_MIDDLE)
                        a.haptic(true)
                    }
                }
            }
            scrolling && s.scrollMomentum -> {
                val v = velocity.calculateVelocity()
                var vx = if (axis == Axis.VERTICAL) 0f else v.x
                var vy = if (axis == Axis.HORIZONTAL) 0f else v.y
                val speed = hypot(vx, vy)
                if (speed > 300f && (upTime - lastTime) < 80) {
                    val cap = MAX_FLING / speed
                    if (cap < 1f) {
                        vx *= cap
                        vy *= cap
                    }
                    motion.fling = scope.launch { fling(motion, vx, vy, AppSettings::padDirection, settings, actions) }
                }
            }
        }
    }
}

/** Keeps scrolling after the fingers lift, slowing down smoothly. Velocities are in dp per second. */
private suspend fun fling(
    motion: TrackpadMotion,
    startVx: Float,
    startVy: Float,
    direction: (AppSettings) -> Float,
    settings: () -> AppSettings,
    actions: () -> PointerActions,
) {
    var vx = startVx
    var vy = startVy
    var last = withFrameNanos { it }
    while (abs(vx) + abs(vy) > 40f) {
        val now = withFrameNanos { it }
        val dt = (now - last) / 1_000_000_000f
        last = now
        val s = settings()
        motion.scroll(vx * dt, vy * dt, direction(s), s, actions())
        val decay = exp(-dt * 3.2f)
        vx *= decay
        vy *= decay
    }
}
