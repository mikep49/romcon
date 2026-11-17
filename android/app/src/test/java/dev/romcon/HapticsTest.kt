package dev.romcon

import dev.romcon.input.Feel
import dev.romcon.input.Haptics
import dev.romcon.input.pulse
import dev.romcon.input.scale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {
    private val levels = (Haptics.MIN_INTENSITY..Haptics.MAX_INTENSITY step 10).toList()

    @Test
    fun strongerSettingsNeverFeelWeaker() {
        Feel.entries.forEach { feel ->
            levels.zipWithNext().forEach { (lo, hi) ->
                assertTrue("$feel scale at $lo vs $hi", feel.scale(hi) > feel.scale(lo))
                assertTrue("$feel amplitude at $lo vs $hi", feel.pulse(hi, true).amplitude >= feel.pulse(lo, true).amplitude)
                assertTrue("$feel length at $lo vs $hi", feel.pulse(hi, false).ms >= feel.pulse(lo, false).ms)
            }
        }
    }

    @Test
    fun releaseAndNotchesAreLighterThanAClick() {
        levels.forEach { level ->
            val click = Feel.CLICK.pulse(level, true)
            assertTrue(Feel.FIRM.scale(level) >= Feel.CLICK.scale(level))
            assertTrue(Feel.RELEASE.scale(level) < Feel.CLICK.scale(level))
            assertTrue(Feel.NOTCH.scale(level) < Feel.RELEASE.scale(level))
            assertTrue(Feel.RELEASE.pulse(level, true).amplitude < click.amplitude)
            assertTrue(Feel.RELEASE.pulse(level, false).ms < Feel.CLICK.pulse(level, false).ms)
        }
    }

    @Test
    fun valuesStayInRangeEvenForOddSettings() {
        Feel.entries.forEach { feel ->
            listOf(-50, 0, 1, Haptics.MIN_INTENSITY, Haptics.MAX_INTENSITY, 500).forEach { level ->
                assertTrue(feel.scale(level) in 0f..1f)
                assertTrue(feel.pulse(level, true).amplitude in 1..255)
                assertTrue(feel.pulse(level, false).ms >= 3)
            }
        }
        // Out-of-range settings are clamped to the nearest one the slider offers.
        assertEquals(Feel.CLICK.scale(Haptics.MAX_INTENSITY), Feel.CLICK.scale(500))
        assertEquals(Feel.CLICK.scale(Haptics.MIN_INTENSITY), Feel.CLICK.scale(0))
        assertEquals(255, Feel.FIRM.pulse(Haptics.MAX_INTENSITY, true).amplitude)
    }
}
