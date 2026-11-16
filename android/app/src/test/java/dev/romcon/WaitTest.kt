package dev.romcon

import dev.romcon.data.CustomShortcut
import dev.romcon.data.KeyCombo
import dev.romcon.data.Wait
import dev.romcon.input.Vk
import dev.romcon.net.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitTest {
    @Test
    fun waitsReadInSeconds() {
        assertEquals("0.5 s", Wait.text(500))
        assertEquals("0.25 s", Wait.text(250))
        assertEquals("2 s", Wait.text(2000))
        assertEquals("1.5 s", Wait.text(1500))
        assertEquals("0.01 s", Wait.text(Wait.MIN_MS))
        assertEquals("60 s", Wait.text(Wait.MAX_MS))
    }

    @Test
    fun typedSecondsBecomeMilliseconds() {
        assertEquals(500, Wait.parse("0.5"))
        assertEquals(500, Wait.parse("0,5"))
        assertEquals(500, Wait.parse(" .5 "))
        assertEquals(2000, Wait.parse("2"))
        assertEquals(1234, Wait.parse("1.234"))
        assertEquals(60_000, Wait.parse("60"))
    }

    @Test
    fun waitsOutsideTheRangeAreRefused() {
        assertNull(Wait.parse(""))
        assertNull(Wait.parse("0"))
        assertNull(Wait.parse("0.001"))
        assertNull(Wait.parse("60.5"))
        assertNull(Wait.parse("half"))
        assertNull(Wait.parse("1.2.3"))
    }

    @Test
    fun everyPresetFitsTheRangeAndReadsBack() {
        Wait.PRESETS.forEach { assertEquals(it, Wait.parse(Wait.seconds(it))) }
    }

    @Test
    fun keysShowTheirWaitsButAnUnnamedButtonOnlyItsKeys() {
        val sc = CustomShortcut("a", "", listOf(KeyCombo(Vk.f(12)), KeyCombo(Vk.f(8), waitMs = 500), KeyCombo(Vk.letter('S'), Proto.MOD_CTRL)))
        assertEquals("F12, wait 0.5 s, F8, Ctrl+S", sc.keysText)
        assertEquals("F12, F8, Ctrl+S", sc.title)
    }

    @Test
    fun onlyAWaitBetweenHotkeysCounts() {
        assertFalse(CustomShortcut("a", "", listOf(KeyCombo(Vk.f(12)), KeyCombo(Vk.f(8)))).waits)
        assertTrue(CustomShortcut("a", "", listOf(KeyCombo(Vk.f(12)), KeyCombo(Vk.f(8), waitMs = 100))).waits)
        assertFalse(CustomShortcut("a", "", listOf(KeyCombo(Vk.f(12), waitMs = 100))).waits)
    }

    @Test
    fun removingTheFirstHotkeyDropsTheWaitBeforeTheNext() {
        val sc = CustomShortcut("a", "", listOf(KeyCombo(Vk.f(12)), KeyCombo(Vk.f(8), waitMs = 500), KeyCombo(Vk.f(9), waitMs = 250)))
        assertEquals(listOf(KeyCombo(Vk.f(8)), KeyCombo(Vk.f(9), waitMs = 250)), sc.withoutKey(0).keys)
        assertEquals(listOf(KeyCombo(Vk.f(12)), KeyCombo(Vk.f(9), waitMs = 250)), sc.withoutKey(1).keys)
    }
}
