package dev.romcon.input

import android.view.KeyEvent
import dev.romcon.net.Proto

/** Windows virtual-key codes the app sends. */
object Vk {
    const val BACK = 0x08
    const val TAB = 0x09
    const val ENTER = 0x0D
    const val SHIFT = 0x10
    const val CTRL = 0x11
    const val ALT = 0x12
    const val PAUSE = 0x13
    const val CAPS_LOCK = 0x14
    const val ESC = 0x1B
    const val SPACE = 0x20
    const val PAGE_UP = 0x21
    const val PAGE_DOWN = 0x22
    const val END = 0x23
    const val HOME = 0x24
    const val LEFT = 0x25
    const val UP = 0x26
    const val RIGHT = 0x27
    const val DOWN = 0x28
    const val PRINT_SCREEN = 0x2C
    const val INSERT = 0x2D
    const val DELETE = 0x2E
    const val WIN = 0x5B
    const val APPS = 0x5D
    const val NUMPAD_0 = 0x60
    const val NUMPAD_MULTIPLY = 0x6A
    const val NUMPAD_ADD = 0x6B
    const val NUMPAD_SUBTRACT = 0x6D
    const val NUMPAD_DECIMAL = 0x6E
    const val NUMPAD_DIVIDE = 0x6F
    const val F1 = 0x70
    const val VOLUME_MUTE = 0xAD
    const val VOLUME_DOWN = 0xAE
    const val VOLUME_UP = 0xAF
    const val MEDIA_NEXT = 0xB0
    const val MEDIA_PREV = 0xB1
    const val MEDIA_STOP = 0xB2
    const val MEDIA_PLAY_PAUSE = 0xB3

    // Symbol keys. Which character each one types depends on the PC's layout; the names are a US keyboard's.
    const val OEM_SEMICOLON = 0xBA
    const val OEM_PLUS = 0xBB
    const val OEM_COMMA = 0xBC
    const val OEM_MINUS = 0xBD
    const val OEM_PERIOD = 0xBE
    const val OEM_SLASH = 0xBF
    const val OEM_BACKTICK = 0xC0
    const val OEM_OPEN_BRACKET = 0xDB
    const val OEM_BACKSLASH = 0xDC
    const val OEM_CLOSE_BRACKET = 0xDD
    const val OEM_QUOTE = 0xDE

    fun letter(c: Char): Int = c.uppercaseChar().code
    fun f(n: Int): Int = F1 + n - 1
}

/** A key on the PC that a custom shortcut can press. */
data class PcKey(val vk: Int, val name: String)

/** Every key a custom shortcut can press, in the groups the key picker shows them in. */
object PcKeys {
    class Group(val title: String, val keys: List<PcKey>, val note: String? = null)

    val groups: List<Group> = listOf(
        Group(
            "Common",
            listOf(
                PcKey(Vk.SPACE, "Space"),
                PcKey(Vk.ENTER, "Enter"),
                PcKey(Vk.TAB, "Tab"),
                PcKey(Vk.ESC, "Esc"),
                PcKey(Vk.BACK, "Backspace"),
                PcKey(Vk.DELETE, "Delete"),
                PcKey(Vk.INSERT, "Insert"),
                PcKey(Vk.HOME, "Home"),
                PcKey(Vk.END, "End"),
                PcKey(Vk.PAGE_UP, "Page Up"),
                PcKey(Vk.PAGE_DOWN, "Page Down"),
                PcKey(Vk.LEFT, "Left"),
                PcKey(Vk.UP, "Up"),
                PcKey(Vk.DOWN, "Down"),
                PcKey(Vk.RIGHT, "Right"),
                PcKey(Vk.PRINT_SCREEN, "Print Screen"),
                PcKey(Vk.PAUSE, "Pause"),
                PcKey(Vk.CAPS_LOCK, "Caps Lock"),
                PcKey(Vk.APPS, "Menu"),
            ),
        ),
        Group("Letters", ('A'..'Z').map { PcKey(Vk.letter(it), it.toString()) }),
        Group("Numbers", ('0'..'9').map { PcKey(it.code, it.toString()) }),
        Group("Function keys", (1..24).map { PcKey(Vk.f(it), "F$it") }),
        Group(
            "Symbols",
            listOf(
                PcKey(Vk.OEM_BACKTICK, "`"),
                PcKey(Vk.OEM_MINUS, "-"),
                PcKey(Vk.OEM_PLUS, "="),
                PcKey(Vk.OEM_OPEN_BRACKET, "["),
                PcKey(Vk.OEM_CLOSE_BRACKET, "]"),
                PcKey(Vk.OEM_BACKSLASH, "\\"),
                PcKey(Vk.OEM_SEMICOLON, ";"),
                PcKey(Vk.OEM_QUOTE, "'"),
                PcKey(Vk.OEM_COMMA, ","),
                PcKey(Vk.OEM_PERIOD, "."),
                PcKey(Vk.OEM_SLASH, "/"),
            ),
            note = "Where these keys are on a US keyboard.",
        ),
        Group(
            "Number pad",
            (0..9).map { PcKey(Vk.NUMPAD_0 + it, "Num $it") } + listOf(
                PcKey(Vk.NUMPAD_DIVIDE, "Num /"),
                PcKey(Vk.NUMPAD_MULTIPLY, "Num *"),
                PcKey(Vk.NUMPAD_SUBTRACT, "Num -"),
                PcKey(Vk.NUMPAD_ADD, "Num +"),
                PcKey(Vk.NUMPAD_DECIMAL, "Num ."),
            ),
        ),
        Group(
            "Media",
            listOf(
                PcKey(Vk.MEDIA_PLAY_PAUSE, "Play/Pause"),
                PcKey(Vk.MEDIA_PREV, "Previous track"),
                PcKey(Vk.MEDIA_NEXT, "Next track"),
                PcKey(Vk.MEDIA_STOP, "Stop"),
                PcKey(Vk.VOLUME_MUTE, "Mute"),
                PcKey(Vk.VOLUME_DOWN, "Volume down"),
                PcKey(Vk.VOLUME_UP, "Volume up"),
            ),
        ),
        Group(
            "Modifier keys",
            listOf(PcKey(Vk.CTRL, "Ctrl"), PcKey(Vk.ALT, "Alt"), PcKey(Vk.SHIFT, "Shift"), PcKey(Vk.WIN, "Win")),
            note = "On their own, for apps that open when you press one, or press it twice.",
        ),
    )

    private val byVk = groups.flatMap { it.keys }.associateBy { it.vk }

    fun isKnown(vk: Int): Boolean = vk in byVk

    fun name(vk: Int): String = byVk[vk]?.name ?: "Key ${vk.toString(16).uppercase()}"
}

/** Maps Android keys that don't type a character (arrows, Delete, F-keys...) to Windows keys. */
fun nonPrintingVk(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_DEL -> Vk.BACK
    KeyEvent.KEYCODE_FORWARD_DEL -> Vk.DELETE
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Vk.ENTER
    KeyEvent.KEYCODE_TAB -> Vk.TAB
    KeyEvent.KEYCODE_ESCAPE -> Vk.ESC
    KeyEvent.KEYCODE_DPAD_LEFT -> Vk.LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> Vk.RIGHT
    KeyEvent.KEYCODE_DPAD_UP -> Vk.UP
    KeyEvent.KEYCODE_DPAD_DOWN -> Vk.DOWN
    KeyEvent.KEYCODE_MOVE_HOME -> Vk.HOME
    KeyEvent.KEYCODE_MOVE_END -> Vk.END
    KeyEvent.KEYCODE_PAGE_UP -> Vk.PAGE_UP
    KeyEvent.KEYCODE_PAGE_DOWN -> Vk.PAGE_DOWN
    KeyEvent.KEYCODE_INSERT -> Vk.INSERT
    KeyEvent.KEYCODE_SYSRQ -> Vk.PRINT_SCREEN
    KeyEvent.KEYCODE_BREAK -> Vk.PAUSE
    KeyEvent.KEYCODE_CAPS_LOCK -> Vk.CAPS_LOCK
    KeyEvent.KEYCODE_MENU -> Vk.APPS
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Vk.MEDIA_PLAY_PAUSE
    KeyEvent.KEYCODE_MEDIA_NEXT -> Vk.MEDIA_NEXT
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Vk.MEDIA_PREV
    KeyEvent.KEYCODE_MEDIA_STOP -> Vk.MEDIA_STOP
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Vk.F1 + (keyCode - KeyEvent.KEYCODE_F1)
    else -> null
}

/** Letters and digits as Windows keys, so shortcuts like Ctrl+C work on any PC keyboard layout. */
fun letterOrDigitVk(c: Char): Int? = when (c) {
    in 'a'..'z', in 'A'..'Z' -> Vk.letter(c)
    in '0'..'9' -> c.code
    else -> null
}

/** Modifier keys you can latch on screen: tap for the next key, long-press to lock. */
enum class ModKey(val label: String, val bit: Int) {
    CTRL("Ctrl", Proto.MOD_CTRL),
    ALT("Alt", Proto.MOD_ALT),
    SHIFT("Shift", Proto.MOD_SHIFT),
    WIN("Win", Proto.MOD_WIN),
}

enum class Latch { OFF, ONCE, LOCKED }
