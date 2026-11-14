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
    const val F1 = 0x70
    const val VOLUME_MUTE = 0xAD
    const val VOLUME_DOWN = 0xAE
    const val VOLUME_UP = 0xAF
    const val MEDIA_NEXT = 0xB0
    const val MEDIA_PREV = 0xB1
    const val MEDIA_STOP = 0xB2
    const val MEDIA_PLAY_PAUSE = 0xB3
    const val OEM_PLUS = 0xBB
    const val OEM_MINUS = 0xBD

    fun letter(c: Char): Int = c.uppercaseChar().code
    fun f(n: Int): Int = F1 + n - 1
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
