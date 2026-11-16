package dev.romcon.data

import dev.romcon.input.ModKey
import dev.romcon.input.PcKeys
import java.math.BigDecimal
import java.util.UUID
import kotlin.math.roundToInt

/**
 * One hotkey: [vk] pressed with the [mods] held (Proto.MOD_* bits). A [vk] of 0 means no key has been chosen yet.
 * In a shortcut, [waitMs] is how long to wait after the hotkey before this one; the first hotkey's is always 0.
 */
data class KeyCombo(val vk: Int = 0, val mods: Int = 0, val waitMs: Int = 0) {
    val chosen: Boolean get() = vk != 0

    /** Like "Ctrl+Shift+T". */
    val text: String
        get() {
            val parts = ModKey.entries.filter { (mods and it.bit) != 0 }.map { it.label }
            return (parts + if (chosen) PcKeys.name(vk) else "…").joinToString("+")
        }
}

/** Waits between hotkeys, and how they're written: "0.5 s", "2 s". */
object Wait {
    const val MIN_MS = 10
    const val MAX_MS = 60_000

    /** What the wait picker offers with one tap. */
    val PRESETS = listOf(100, 250, 500, 1000, 2000, 5000)

    fun text(ms: Int): String = "${seconds(ms)} s"

    /** Like "0.5" or "2", for a text field. */
    fun seconds(ms: Int): String = BigDecimal(ms).movePointLeft(3).stripTrailingZeros().toPlainString()

    /** Seconds as typed ("0.5", "0,5", "2"), in milliseconds; null unless it's between [MIN_MS] and [MAX_MS]. */
    fun parse(text: String): Int? {
        val seconds = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (seconds.isNaN()) return null
        return (seconds * 1000).roundToInt().takeIf { it in MIN_MS..MAX_MS }
    }
}

/**
 * A bottom-bar button the user made, which presses one or more hotkeys on the PC in turn, maybe with waits between them.
 * Its icon is a picture from the phone when [image] (a file name in [ShortcutImages]) is set, and otherwise
 * the built-in icon named [icon].
 */
data class CustomShortcut(
    val id: String,
    val name: String,
    val keys: List<KeyCombo>,
    val icon: String = DEFAULT_ICON,
    val image: String? = null,
) {
    /** Like "Ctrl+K, Ctrl+C", or "F12, wait 0.5 s, F8". */
    val keysText: String
        get() = keys.mapIndexed { i, k -> if (i > 0 && k.waitMs > 0) "wait ${Wait.text(k.waitMs)}, ${k.text}" else k.text }.joinToString(", ")

    /** It waits somewhere, so pressing it takes a while. */
    val waits: Boolean get() = keys.drop(1).any { it.waitMs > 0 }

    /** What the button is called: its name, or when it has none, its keys (without the waits, which are long to show). */
    val title: String get() = name.trim().ifEmpty { keys.joinToString(", ") { it.text } }

    /** Every hotkey has a key, so it can be saved. */
    val ready: Boolean get() = keys.isNotEmpty() && keys.all { it.chosen }

    /** Without the hotkey at [index]. A wait only goes between two hotkeys, so the new first one loses its wait. */
    fun withoutKey(index: Int): CustomShortcut =
        copy(keys = keys.filterIndexed { i, _ -> i != index }.mapIndexed { i, k -> if (i == 0) k.copy(waitMs = 0) else k })

    companion object {
        const val MAX_KEYS = 4
        const val MAX_NAME = 30
        const val DEFAULT_ICON = "command"

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

/** A button on the bar under the trackpad: one of RomCon's own, or a custom shortcut, by its id. */
sealed interface BarItem {
    data class Builtin(val button: BarButton) : BarItem

    data class Custom(val id: String) : BarItem

    companion object {
        const val MAX = 5
        val DEFAULT: List<BarItem> = listOf(BarButton.KEYBOARD, BarButton.WINDOWS, BarButton.SHORTCUTS).map(::Builtin)

        private const val CUSTOM = "custom:"

        /**
         * Reads a saved bar, dropping buttons this version doesn't know and shortcuts that no longer exist.
         * An unusable value gives the default bar.
         */
        fun parse(saved: String?, shortcutIds: Set<String>): List<BarItem> {
            if (saved == null) return DEFAULT
            val items = saved.split(',').mapNotNull { name ->
                if (name.startsWith(CUSTOM)) {
                    name.removePrefix(CUSTOM).takeIf { it in shortcutIds }?.let(::Custom)
                } else {
                    BarButton.entries.firstOrNull { it.name == name }?.let(::Builtin)
                }
            }
            return items.distinct().take(MAX).ifEmpty { DEFAULT }
        }

        fun format(items: List<BarItem>): String = items.joinToString(",") {
            when (it) {
                is Builtin -> it.button.name
                is Custom -> CUSTOM + it.id
            }
        }
    }
}
