package dev.romcon

import dev.romcon.input.TypedLine
import dev.romcon.input.Vk
import dev.romcon.net.Proto
import org.junit.Assert.assertEquals
import org.junit.Test

class TypedLineTest {
    private fun typed(text: String) = TypedLine().insert(text)

    private fun TypedLine.keys(vararg vks: Int) = vks.fold(this) { line, vk -> line.key(vk, 0) }

    @Test
    fun typingAddsAtTheCaret() {
        assertEquals(TypedLine("hello", 5), typed("hel").insert("lo"))
        assertEquals(TypedLine("hello", 3), typed("helo").keys(Vk.LEFT).insert("l").keys(Vk.LEFT))
    }

    @Test
    fun backspaceAndDeleteRemoveOneCharacter() {
        assertEquals(TypedLine("hell", 4), typed("hello").keys(Vk.BACK))
        assertEquals(TypedLine("hllo", 1), typed("hello").keys(Vk.LEFT, Vk.LEFT, Vk.LEFT, Vk.BACK))
        assertEquals(TypedLine("helo", 3), typed("hello").keys(Vk.LEFT, Vk.LEFT, Vk.DELETE))
    }

    @Test
    fun anEmojiIsOneCharacter() {
        assertEquals(TypedLine("hi", 2), typed("hi😀").keys(Vk.BACK))
        assertEquals(TypedLine("hi😀", 2), typed("hi😀").keys(Vk.LEFT))
        assertEquals(TypedLine("hi", 2), typed("hi😀").keys(Vk.LEFT, Vk.DELETE))
    }

    @Test
    fun theCaretStopsAtTheEnds() {
        assertEquals(TypedLine("ab", 0), typed("ab").keys(Vk.LEFT, Vk.LEFT, Vk.LEFT, Vk.BACK))
        assertEquals(TypedLine("ab", 2), typed("ab").keys(Vk.RIGHT, Vk.DELETE))
    }

    @Test
    fun enterStartsANewLine() {
        assertEquals(TypedLine.EMPTY, typed("hello").keys(Vk.ENTER))
        assertEquals(TypedLine("two", 3), typed("zero").insert("one\ntwo"))
        assertEquals(TypedLine.EMPTY, typed("hello").insert("\n"))
    }

    @Test
    fun spaceKeyTypesASpace() {
        assertEquals(TypedLine("a b", 3), typed("a").keys(Vk.SPACE).insert("b"))
    }

    @Test
    fun keysThatMoveTheCaretElsewhereStartOver() {
        for (vk in listOf(Vk.UP, Vk.DOWN, Vk.HOME, Vk.END, Vk.TAB, Vk.ESC, Vk.WIN, Vk.f(5))) {
            assertEquals(TypedLine.EMPTY, typed("hello").keys(vk))
        }
    }

    @Test
    fun shortcutsStartOver() {
        assertEquals(TypedLine.EMPTY, typed("hello").key(Vk.letter('A'), Proto.MOD_CTRL))
        assertEquals(TypedLine.EMPTY, typed("hello").key(Vk.LEFT, Proto.MOD_SHIFT))
        assertEquals(TypedLine.EMPTY, typed("hello").key(Vk.BACK, Proto.MOD_CTRL))
    }

    @Test
    fun volumeAndMediaKeysChangeNothing() {
        val line = typed("hello")
        for (vk in listOf(Vk.VOLUME_UP, Vk.VOLUME_DOWN, Vk.VOLUME_MUTE, Vk.MEDIA_PLAY_PAUSE, Vk.MEDIA_NEXT, Vk.PRINT_SCREEN)) {
            assertEquals(line, line.key(vk, 0))
        }
    }

    @Test
    fun veryLongTextKeepsItsEnd() {
        val line = typed("a".repeat(TypedLine.MAX_LENGTH)).insert("xyz")
        assertEquals(TypedLine.MAX_LENGTH, line.text.length)
        assertEquals(TypedLine.MAX_LENGTH, line.cursor)
        assertEquals("xyz", line.text.takeLast(3))
    }
}
