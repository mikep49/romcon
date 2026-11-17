package dev.romcon.input

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

/**
 * What a moment on the trackpad feels like, modelled on a laptop's haptic touchpad: a crisp click as a button goes
 * down, a lighter one as it comes back up. [weight] is how strong each is next to a full-strength click.
 */
enum class Feel(internal val weight: Float) {
    /** A button going down: a tap on the pad, the start of a drag, a key or a button on the screen. */
    CLICK(0.8f),

    /** A firmer click, for right and middle clicks and for locking a modifier key on. */
    FIRM(1f),

    /** A held mouse button coming back up: lighter than the click, like a touchpad letting go. */
    RELEASE(0.55f),

    /** One mouse-wheel notch while sliding along the scroll bar: the lightest tick. */
    NOTCH(0.4f),
}

/** The phone's vibration motor, played at a strength the user picks rather than Android's fixed touch feedback. */
class Haptics(context: Context) {
    private val resolver = context.contentResolver

    /** Null on phones (and tablets) without a vibration motor. */
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }?.takeIf { it.hasVibrator() }

    val available: Boolean get() = vibrator != null

    /** Phones with a good motor play Android's tuned click and tick; the rest get a short buzz. */
    private val crisp: Boolean = vibrator != null && Build.VERSION.SDK_INT >= 30 &&
        vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK)

    private val amplitudeControl: Boolean = vibrator?.hasAmplitudeControl() == true

    /** Before Android 13, sonification is how a vibration says it's touch feedback. */
    @Suppress("DEPRECATION")
    private val legacyAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private var lastNotch = 0L

    /** Plays [feel] at [intensity] percent. */
    fun play(feel: Feel, intensity: Int) {
        val v = vibrator ?: return
        if (feel == Feel.NOTCH) {
            // A fast slide passes notches quicker than the motor can tick; skipping some keeps it from turning into a buzz.
            val now = SystemClock.uptimeMillis()
            if (now - lastNotch < NOTCH_GAP_MS) return
            lastNotch = now
        }
        val effect = if (Build.VERSION.SDK_INT >= 30 && crisp) {
            crispEffect(feel, intensity)
        } else {
            feel.pulse(intensity, amplitudeControl).effect()
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, legacyAttributes)
            }
        } catch (_: RuntimeException) {
            // Some phones refuse vibrations in odd states (a power-saving mode, say); there's nothing to tell the user.
        }
    }

    @RequiresApi(30)
    private fun crispEffect(feel: Feel, intensity: Int): VibrationEffect {
        val primitive = when (feel) {
            Feel.CLICK, Feel.FIRM -> VibrationEffect.Composition.PRIMITIVE_CLICK
            Feel.RELEASE, Feel.NOTCH -> VibrationEffect.Composition.PRIMITIVE_TICK
        }
        return VibrationEffect.startComposition().addPrimitive(primitive, feel.scale(intensity)).compose()
    }

    /**
     * True when Android's own touch feedback is switched off, which also silences RomCon: from Android 13 the
     * system applies that switch to every app's touch vibrations. Only checked from there, where that holds.
     */
    fun silencedBySystem(): Boolean {
        if (vibrator == null || Build.VERSION.SDK_INT < 33) return false
        return try {
            Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0
        } catch (_: RuntimeException) {
            false
        }
    }

    companion object {
        const val MIN_INTENSITY = 10
        const val MAX_INTENSITY = 100
        const val DEFAULT_INTENSITY = 70

        private const val NOTCH_GAP_MS = 30L
    }
}

/** One buzz of the motor: [ms] long at [amplitude], 1–255, or [VibrationEffect.DEFAULT_AMPLITUDE] on a motor with one strength. */
internal data class Pulse(val ms: Long, val amplitude: Int) {
    fun effect(): VibrationEffect = VibrationEffect.createOneShot(ms, amplitude)
}

private fun fraction(intensity: Int): Float =
    intensity.coerceIn(Haptics.MIN_INTENSITY, Haptics.MAX_INTENSITY) / 100f

/** How strongly a tuned click or tick plays, 0–1. */
internal fun Feel.scale(intensity: Int): Float = (fraction(intensity) * weight).coerceIn(0f, 1f)

/**
 * The buzz that stands in for [this] on a phone without tuned effects. A motor with amplitude control plays a short,
 * fixed-length pulse at the chosen strength; one without can only buzz for less time to feel lighter.
 */
internal fun Feel.pulse(intensity: Int, amplitudeControl: Boolean): Pulse {
    val k = fraction(intensity)
    val ms = when (this) {
        Feel.CLICK -> 14L
        Feel.FIRM -> 20L
        Feel.RELEASE -> 9L
        Feel.NOTCH -> 6L
    }
    return if (amplitudeControl) {
        Pulse(ms, (255 * k * weight).roundToInt().coerceIn(1, 255))
    } else {
        Pulse((ms * (0.35f + 0.65f * k)).roundToInt().toLong().coerceAtLeast(3), VibrationEffect.DEFAULT_AMPLITUDE)
    }
}
