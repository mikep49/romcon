package dev.romcon

import dev.romcon.media.BackgroundLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundLimitTest {
    @Test
    fun choicesReadAsTimes() {
        assertEquals(
            listOf("30 minutes", "1 hour", "2 hours", "4 hours", "8 hours", "No limit"),
            BackgroundLimit.CHOICES.map(BackgroundLimit::label),
        )
    }

    @Test
    fun noLimitIsTheLastChoice() {
        // Settings lists the choices in order, shortest first, with "No limit" at the end.
        assertEquals(BackgroundLimit.NONE, BackgroundLimit.CHOICES.last())
        assertEquals(BackgroundLimit.CHOICES.dropLast(1).sorted(), BackgroundLimit.CHOICES.dropLast(1))
    }

    @Test
    fun deadlineCountsFromClosing() {
        val closedAt = 1_800_000_000_000L
        assertEquals(closedAt + 60 * 60_000L, BackgroundLimit.deadline(60, closedAt))
        assertEquals(closedAt + 8 * 60 * 60_000L, BackgroundLimit.deadline(480, closedAt))
        assertNull(BackgroundLimit.deadline(BackgroundLimit.NONE, closedAt))
    }
}
