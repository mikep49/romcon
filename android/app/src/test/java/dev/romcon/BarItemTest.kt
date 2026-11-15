package dev.romcon

import dev.romcon.data.BarButton
import dev.romcon.data.BarItem
import dev.romcon.data.CustomShortcut
import dev.romcon.data.KeyCombo
import dev.romcon.input.PcKeys
import dev.romcon.input.Vk
import dev.romcon.net.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarItemTest {
    private fun builtins(vararg buttons: BarButton) = buttons.map(BarItem::Builtin)

    @Test
    fun nothingSavedGivesTheDefaultBar() {
        assertEquals(builtins(BarButton.KEYBOARD, BarButton.WINDOWS, BarButton.SHORTCUTS), BarItem.parse(null, emptySet()))
    }

    @Test
    fun savedBarKeepsItsOrder() {
        val bar = listOf(BarItem.Builtin(BarButton.SHORTCUTS), BarItem.Custom("abc123"), BarItem.Builtin(BarButton.KEYBOARD))
        assertEquals(bar, BarItem.parse(BarItem.format(bar), setOf("abc123")))
    }

    @Test
    fun barsSavedBeforeCustomShortcutsStillLoad() {
        assertEquals(builtins(BarButton.SHORTCUTS, BarButton.LEFT_CLICK), BarItem.parse("SHORTCUTS,LEFT_CLICK", emptySet()))
    }

    @Test
    fun unknownAndRepeatedNamesAreDropped() {
        assertEquals(builtins(BarButton.COPY, BarButton.PASTE), BarItem.parse("COPY,HOLOGRAM,PASTE,COPY", emptySet()))
    }

    @Test
    fun deletedShortcutsAreDropped() {
        assertEquals(
            listOf(BarItem.Builtin(BarButton.COPY), BarItem.Custom("kept")),
            BarItem.parse("COPY,custom:gone,custom:kept", setOf("kept")),
        )
    }

    @Test
    fun nothingUsableGivesTheDefaultBar() {
        assertEquals(BarItem.DEFAULT, BarItem.parse("", emptySet()))
        assertEquals(BarItem.DEFAULT, BarItem.parse("HOLOGRAM", emptySet()))
        assertEquals(BarItem.DEFAULT, BarItem.parse("custom:gone", emptySet()))
    }

    @Test
    fun barNeverHoldsMoreThanFits() {
        val all = BarItem.format(BarButton.entries.map(BarItem::Builtin))
        assertEquals(BarButton.entries.take(BarItem.MAX).map(BarItem::Builtin), BarItem.parse(all, emptySet()))
    }

    @Test
    fun hotkeysReadLikeWindowsWritesThem() {
        assertEquals("Alt+Space", KeyCombo(Vk.SPACE, Proto.MOD_ALT).text)
        assertEquals("Ctrl+Shift+Esc", KeyCombo(Vk.ESC, Proto.MOD_SHIFT or Proto.MOD_CTRL).text)
        assertEquals("Ctrl+Alt+Shift+Win+F13", KeyCombo(Vk.f(13), 0xF).text)
        assertEquals("Win+…", KeyCombo(0, Proto.MOD_WIN).text)
    }

    @Test
    fun shortcutWithoutANameIsCalledByItsKeys() {
        val keys = listOf(KeyCombo(Vk.letter('K'), Proto.MOD_CTRL), KeyCombo(Vk.letter('C'), Proto.MOD_CTRL))
        assertEquals("Ctrl+K, Ctrl+C", CustomShortcut("a", "  ", keys).title)
        assertEquals("Comment", CustomShortcut("a", " Comment ", keys).title)
    }

    @Test
    fun shortcutNeedsEveryKeyChosen() {
        assertTrue(CustomShortcut("a", "", listOf(KeyCombo(Vk.SPACE, Proto.MOD_ALT))).ready)
        assertFalse(CustomShortcut("a", "", listOf(KeyCombo(Vk.SPACE), KeyCombo(0, Proto.MOD_CTRL))).ready)
        assertFalse(CustomShortcut("a", "", emptyList()).ready)
    }

    @Test
    fun everyPickerKeyHasOneName() {
        val keys = PcKeys.groups.flatMap { it.keys }
        assertEquals(keys.size, keys.map { it.vk }.toSet().size)
        assertTrue(keys.all { it.vk in 1..0xFE })
    }
}
