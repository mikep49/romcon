package dev.romcon

import dev.romcon.net.FrameBuilder
import dev.romcon.net.Proto
import dev.romcon.net.readFrame
import dev.romcon.net.verifyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class ProtocolTest {
    @Test
    fun framesRoundTrip() {
        val bytes = FrameBuilder(Proto.HELLO).u8(Proto.VERSION).str("id-1").str("Pixel ✓").u8(Proto.AUTH_PIN).str("123456").build()
        val input = ByteArrayInputStream(bytes + FrameBuilder(Proto.PING).build())
        val hello = readFrame(input)!!
        assertEquals(Proto.HELLO, hello.type)
        assertEquals(Proto.VERSION, hello.u8())
        assertEquals("id-1", hello.str())
        assertEquals("Pixel ✓", hello.str())
        assertEquals(Proto.AUTH_PIN, hello.u8())
        assertEquals("123456", hello.str())
        assertEquals(Proto.PING, readFrame(input)!!.type)
        assertNull(readFrame(input))
    }

    @Test
    fun negativeDeltasSurviveAsSigned16Bit() {
        val f = readFrame(ByteArrayInputStream(FrameBuilder(Proto.MOUSE_MOVE).u16(-5).u16(300).build()))!!
        assertEquals(-5, f.u16().toShort().toInt())
        assertEquals(300, f.u16().toShort().toInt())
    }

    @Test
    fun lengthPrefixCountsTypeAndPayload() {
        val bytes = FrameBuilder(Proto.MOUSE_BUTTON).u8(1).u8(1).build()
        assertEquals(listOf(0, 3, Proto.MOUSE_BUTTON, 1, 1), bytes.map { it.toInt() and 0xFF })
    }

    @Test
    fun verifyCodeMatchesWindowsFormat() {
        assertEquals("B03B 56FB", verifyCode("b03b56fb" + "0".repeat(56)))
    }
}
