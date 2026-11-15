package dev.romcon

import dev.romcon.update.isNewer
import dev.romcon.update.parseVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun readsReleaseTags() {
        assertEquals(listOf(1, 2, 3), parseVersion("v1.2.3"))
        assertEquals(listOf(1, 10), parseVersion("1.10"))
        assertEquals(listOf(2, 0, 0), parseVersion("v2.0.0-beta"))
        assertNull(parseVersion("latest"))
        assertNull(parseVersion(""))
        assertNull(parseVersion(null))
    }

    @Test
    fun comparesNumbersNotText() {
        assertTrue(isNewer("1.0.1", "1.0.0"))
        assertTrue(isNewer("1.10.0", "1.9.0"))
        assertTrue(isNewer("v2", "1.9.9"))
        assertFalse(isNewer("1.0.0", "1.0.0"))
        assertFalse(isNewer("1.0", "1.0.0"))
        assertFalse(isNewer("0.9.9", "1.0.0"))
        assertFalse(isNewer("nonsense", "1.0.0"))
    }
}
