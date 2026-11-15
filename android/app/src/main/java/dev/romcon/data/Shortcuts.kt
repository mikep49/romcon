package dev.romcon.data

import dev.romcon.input.ModKey
import dev.romcon.input.PcKeys
import java.util.UUID

/** One hotkey: [vk] pressed with the [mods] held (Proto.MOD_* bits). A [vk] of 0 means no key has been chosen yet. */
data class KeyCombo(val vk: Int = 0, val mods: Int = 0) {
    val chosen: Boolean get() = vk != 0

    /** Like "Ctrl+Shift+T". */
    val text: String
        get() {
            val parts = ModKey.entries.filter { (mods and it.bit) != 0 }.map { it.label }
            return (parts + if (chosen) PcKeys.name(vk) else "…").joinToString("+")
        }
}

/**
 * A bottom-bar button the user made, which presses one or more hotkeys on the PC in turn.
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
    /** Like "Ctrl+K, Ctrl+C". */
    val keysText: String get() = keys.joinToString(", ") { it.text }

    /** What the button is called: its name, or its keys when it has none. */
    val title: String get() = name.trim().ifEmpty { keysText }

    /** Every hotkey has a key, so it can be saved. */
    val ready: Boolean get() = keys.isNotEmpty() && keys.all { it.chosen }

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
