package dev.romcon

import dev.romcon.net.FrameBuilder
import dev.romcon.net.MediaState
import dev.romcon.net.Proto
import dev.romcon.net.readFrame
import dev.romcon.net.sealDatagram
import dev.romcon.net.verifyCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    fun datagramIsNonceThenSealedFrameCountAndFrames() {
        val key = SecretKeySpec(ByteArray(Proto.UDP_KEY_SIZE) { it.toByte() }, "AES")
        val frames = FrameBuilder(Proto.MOUSE_MOVE).u16(-5).u16(300).build()
        val packet = sealDatagram(Cipher.getInstance("AES/GCM/NoPadding"), key, 0x01020304, 7, 9, frames)
        assertEquals(Proto.UDP_NONCE_SIZE + 4 + frames.size + Proto.UDP_TAG_BITS / 8, packet.size)
        assertEquals(listOf(1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 7), packet.take(Proto.UDP_NONCE_SIZE).map { it.toInt() })

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(Proto.UDP_TAG_BITS, packet, 0, Proto.UDP_NONCE_SIZE))
        val plain = cipher.doFinal(packet, Proto.UDP_NONCE_SIZE, packet.size - Proto.UDP_NONCE_SIZE)
        assertEquals(listOf(0, 0, 0, 9), plain.take(4).map { it.toInt() })
        assertArrayEquals(frames, plain.copyOfRange(4, plain.size))
    }

    @Test
    fun mediaFrameParses() {
        val bytes = FrameBuilder(Proto.MEDIA)
            .u8(Proto.MEDIA_PLAYING).u8(Proto.CAN_PAUSE or Proto.CAN_SEEK).u8(42).u8(1)
            .u32(65_000).u32(-294_967_296).u16(150).u32(7).str("Song ✓").str("")
            .build()
        val media = MediaState.parse(readFrame(ByteArrayInputStream(bytes))!!)
        assertTrue(media.showing && media.playing)
        assertTrue(media.can(Proto.CAN_SEEK) && !media.can(Proto.CAN_NEXT))
        assertEquals(42, media.volume)
        assertTrue(media.muted)
        assertEquals(65_000L, media.positionMs)
        assertEquals(4_000_000_000L, media.durationMs) // an unsigned 32-bit value, not a negative one
        assertEquals(150, media.ratePercent)
        assertEquals(7, media.artId)
        assertEquals("Song ✓", media.title)
        assertEquals("", media.artist)
    }

    @Test
    fun optionalTrailingFieldsCanBeTold() {
        // A WELCOME from a PC that predates media links ends after the token.
        val old = readFrame(ByteArrayInputStream(FrameBuilder(Proto.WELCOME).str("PC").str("").build()))!!
        old.str()
        old.str()
        assertFalse(old.hasMore)
        val new = readFrame(ByteArrayInputStream(FrameBuilder(Proto.WELCOME).str("PC").str("").u8(Proto.FEATURE_MEDIA).build()))!!
        new.str()
        new.str()
        assertTrue(new.hasMore)
        assertEquals(Proto.FEATURE_MEDIA, new.u8())
    }

    @Test
    fun mediaCommandValueKeepsItsSign() {
        val f = readFrame(ByteArrayInputStream(FrameBuilder(Proto.MEDIA_COMMAND).u8(Proto.MEDIA_ADJUST_VOLUME).u32(-2).build()))!!
        assertEquals(Proto.MEDIA_ADJUST_VOLUME, f.u8())
        assertEquals(-2, f.u32())
    }

    @Test
    fun verifyCodeMatchesWindowsFormat() {
        assertEquals("B03B 56FB", verifyCode("b03b56fb" + "0".repeat(56)))
    }
}
