package dev.romcon.ui

import android.content.Context
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.romcon.input.Vk
import dev.romcon.input.nonPrintingVk
import dev.romcon.net.Proto

/** Where captured keystrokes go. [mods] are modifiers held on a hardware keyboard. */
interface KeySink {
    fun onText(text: String)
    fun onKey(vk: Int, mods: Int)
}

/**
 * An invisible view that the soft keyboard types into. It never keeps text: whatever the keyboard commits
 * is forwarded and cleared, and deletions beyond the local buffer become Backspace on the PC. Suggestions and
 * autocorrect are off (visible-password mode), since the phone can't see what's on the PC's screen.
 */
class KeyboardCaptureView(context: Context) : View(context) {
    var sink: KeySink? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_ACTION_NONE
        return Connection()
    }

    private inner class Connection : BaseInputConnection(this, true) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            super.commitText(text, newCursorPosition)
            flush()
            return true
        }

        override fun finishComposingText(): Boolean {
            super.finishComposingText()
            flush()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Characters still being composed live here; anything before them is already on the PC.
            val local = editable?.length ?: 0
            repeat((beforeLength - local).coerceAtLeast(0)) { sink?.onKey(Vk.BACK, 0) }
            repeat(afterLength) { sink?.onKey(Vk.DELETE, 0) }
            return super.deleteSurroundingText(minOf(beforeLength, local), 0)
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            sink?.onKey(Vk.ENTER, 0)
            return true
        }

        private fun flush() {
            val e = editable ?: return
            if (e.isEmpty()) return
            val text = e.toString()
            e.clear()
            sink?.onText(text)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)

    private fun handleKey(event: KeyEvent): Boolean {
        val sink = sink ?: return false
        if (event.isSystem && event.keyCode != KeyEvent.KEYCODE_MENU) return false // volume, back, home...
        var mods = 0
        if (event.isCtrlPressed) mods = mods or Proto.MOD_CTRL
        if (event.isAltPressed) mods = mods or Proto.MOD_ALT
        if (event.isMetaPressed) mods = mods or Proto.MOD_WIN

        nonPrintingVk(event.keyCode)?.let { vk ->
            if (event.isShiftPressed) mods = mods or Proto.MOD_SHIFT
            sink.onKey(vk, mods)
            return true
        }
        val ch = event.getUnicodeChar(event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON))
        if (ch == 0 || (ch and KeyCharacterMap.COMBINING_ACCENT) != 0) return false
        val text = String(Character.toChars(ch))
        if (mods == 0) {
            sink.onText(text)
        } else {
            val vk = when (val c = text[0]) {
                in 'a'..'z', in 'A'..'Z' -> Vk.letter(c)
                in '0'..'9' -> c.code
                ' ' -> Vk.SPACE
                else -> null
            }
            if (vk != null) sink.onKey(vk, mods) else sink.onText(text)
        }
        return true
    }
}

class KeyboardController {
    internal var view: KeyboardCaptureView? = null

    fun show() {
        val v = view ?: return
        v.requestFocus()
        v.post {
            val imm = v.context.getSystemService(InputMethodManager::class.java)
            imm?.restartInput(v)
            imm?.showSoftInput(v, 0)
        }
    }

    fun hide() {
        val v = view ?: return
        val imm = v.context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }
}

@Composable
fun KeyboardCapture(controller: KeyboardController, sink: KeySink) {
    val holder = remember { controller }
    AndroidView(
        factory = { ctx -> KeyboardCaptureView(ctx).also { holder.view = it } },
        update = { it.sink = sink },
        onRelease = { holder.view = null },
        modifier = Modifier
            .size(1.dp)
            .alpha(0f),
    )
}
