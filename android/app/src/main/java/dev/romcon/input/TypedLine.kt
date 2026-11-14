package dev.romcon.input

/**
 * The phone's best guess at the line it is typing on the PC, shown above the keyboard so typos are easy to spot.
 * The phone can't see the PC's screen, so this only follows what it sent itself: typing, Backspace, Delete and
 * Left/Right within the text it typed. Anything else that can move the PC's caret or change its text empties it.
 */
data class TypedLine(val text: String = "", val cursor: Int = 0) {
    fun insert(typed: String): TypedLine {
        if (typed.isEmpty()) return this
        // Enter starts over: only the line being typed is shown.
        val lineBreak = typed.lastIndexOfAny(charArrayOf('\n', '\r'))
        if (lineBreak >= 0) return TypedLine().insert(typed.substring(lineBreak + 1))

        val joined = text.substring(0, cursor) + typed + text.substring(cursor)
        val end = cursor + typed.length
        var drop = (joined.length - MAX_LENGTH).coerceIn(0, end)
        if (drop > 0 && Character.isLowSurrogate(joined[drop])) drop++ // don't cut an emoji in half
        return TypedLine(joined.substring(drop), end - drop)
    }

    /** A key tap sent to the PC, with [mods] held. */
    fun key(vk: Int, mods: Int): TypedLine = when {
        vk in TEXT_SAFE_KEYS -> this
        mods != 0 -> EMPTY
        vk == Vk.SPACE -> insert(" ")
        vk == Vk.BACK -> if (cursor == 0) this else before().let { TypedLine(text.removeRange(it, cursor), it) }
        vk == Vk.DELETE -> if (cursor == text.length) this else copy(text = text.removeRange(cursor, after()))
        // At either end the caret stays put: usually there is nothing more on the PC to move into.
        vk == Vk.LEFT -> if (cursor == 0) this else copy(cursor = before())
        vk == Vk.RIGHT -> if (cursor == text.length) this else copy(cursor = after())
        else -> EMPTY
    }

    private fun before() = text.offsetByCodePoints(cursor, -1)

    private fun after() = text.offsetByCodePoints(cursor, 1)

    companion object {
        val EMPTY = TypedLine()
        const val MAX_LENGTH = 1000

        /** Keys that leave the PC's text and caret alone. */
        private val TEXT_SAFE_KEYS = setOf(
            Vk.VOLUME_MUTE, Vk.VOLUME_DOWN, Vk.VOLUME_UP,
            Vk.MEDIA_NEXT, Vk.MEDIA_PREV, Vk.MEDIA_STOP, Vk.MEDIA_PLAY_PAUSE,
            Vk.PRINT_SCREEN,
        )
    }
}
