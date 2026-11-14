package dev.romcon

import dev.romcon.data.BarButton
import org.junit.Assert.assertEquals
import org.junit.Test

class BarButtonTest {
    @Test
    fun nothingSavedGivesTheDefaultBar() {
        assertEquals(listOf(BarButton.KEYBOARD, BarButton.WINDOWS, BarButton.SHORTCUTS), BarButton.parse(null))
    }

    @Test
    fun savedBarKeepsItsOrder() {
        val bar = listOf(BarButton.SHORTCUTS, BarButton.LEFT_CLICK, BarButton.KEYBOARD)
        assertEquals(bar, BarButton.parse(BarButton.format(bar)))
    }

    @Test
    fun unknownAndRepeatedNamesAreDropped() {
        assertEquals(listOf(BarButton.COPY, BarButton.PASTE), BarButton.parse("COPY,HOLOGRAM,PASTE,COPY"))
    }

    @Test
    fun nothingUsableGivesTheDefaultBar() {
        assertEquals(BarButton.DEFAULT, BarButton.parse(""))
        assertEquals(BarButton.DEFAULT, BarButton.parse("HOLOGRAM"))
    }

    @Test
    fun barNeverHoldsMoreThanFits() {
        val all = BarButton.format(BarButton.entries)
        assertEquals(BarButton.entries.take(BarButton.MAX_ON_BAR), BarButton.parse(all))
    }
}
