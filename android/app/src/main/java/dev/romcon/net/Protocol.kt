package dev.romcon.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Wire constants shared with the Windows app (see docs/protocol.md). */
object Proto {
    const val VERSION = 1
    const val DEFAULT_PORT = 50505
    const val DISCOVERY_PORT = 50506

    /** Where the PC listens when its own port is taken (see the Windows app's Proto.FallbackPorts). */
    val FALLBACK_PORTS = listOf(45505, 46505, 47505)
    const val DISCOVERY_REQUEST = "ROMCON?1"
    const val DISCOVERY_REPLY = "ROMCON!1"
    const val MAX_FRAME = 8192

    // Phone -> PC
    const val HELLO = 0x01
    const val PING = 0x02
    const val UDP_REQUEST = 0x03
    const val UDP_SYNC = 0x04
    const val MOUSE_MOVE = 0x10
    const val MOUSE_BUTTON = 0x11
    const val SCROLL = 0x12
    const val TEXT = 0x20
    const val KEY = 0x21
    const val CHAR_WITH_MODS = 0x22
    const val COMMAND = 0x30
    const val SWITCH_DESKTOP = 0x31
    const val MEDIA_COMMAND = 0x40

    // PC -> phone
    const val WELCOME = 0x81
    const val REJECTED = 0x82
    const val PONG = 0x83
    const val STATUS = 0x84
    const val UDP_OFFER = 0x85
    const val UDP_PONG = 0x86
    const val MEDIA = 0x87
    const val MEDIA_ART = 0x88
    const val DESKTOPS = 0x89

    // A datagram is [u32 channel id][u64 counter][AES-256-GCM ciphertext][16-byte tag].
    const val UDP_KEY_SIZE = 32
    const val UDP_NONCE_SIZE = 12
    const val UDP_TAG_BITS = 128

    const val AUTH_TOKEN = 0
    const val AUTH_PIN = 1

    // What a connection is for: controlling the PC, or only showing its media controls on the phone.
    const val ROLE_CONTROL = 0
    const val ROLE_MEDIA = 1

    // WELCOME feature bits.
    const val FEATURE_MEDIA = 1

    const val REJECT_VERSION = 1
    const val REJECT_NOT_PAIRED = 2
    const val REJECT_WRONG_PIN = 3
    const val REJECT_PAIRING_CLOSED = 4
    const val REJECT_TOO_MANY_ATTEMPTS = 5

    const val KEY_UP = 0
    const val KEY_DOWN = 1
    const val KEY_TAP = 2

    const val MOD_CTRL = 1
    const val MOD_ALT = 2
    const val MOD_SHIFT = 4
    const val MOD_WIN = 8

    const val CMD_LOCK = 1

    const val MEDIA_NONE = 0
    const val MEDIA_PLAYING = 1
    const val MEDIA_PAUSED = 2

    const val CAN_PLAY = 1
    const val CAN_PAUSE = 2
    const val CAN_NEXT = 4
    const val CAN_PREVIOUS = 8
    const val CAN_SEEK = 16
    const val CAN_VOLUME = 32

    const val MEDIA_PLAY = 1
    const val MEDIA_PAUSE = 2
    const val MEDIA_NEXT = 3
    const val MEDIA_PREVIOUS = 4
    const val MEDIA_SEEK_TO = 5
    const val MEDIA_SET_VOLUME = 6
    const val MEDIA_ADJUST_VOLUME = 7

    /** The most artwork the phone accepts, however many MEDIA_ART frames it arrives in. */
    const val MAX_ART_BYTES = 256 * 1024

    const val BUTTON_LEFT = 0
    const val BUTTON_RIGHT = 1
    const val BUTTON_MIDDLE = 2
    const val BUTTON_BACK = 3
    const val BUTTON_FORWARD = 4
}

/** Builds one frame: [u16 length][u8 type][payload], big-endian, length counting type and payload. */
class FrameBuilder(type: Int) {
    private val out = ByteArrayOutputStream(16)

    init {
        out.write(0)
        out.write(0)
        out.write(type)
    }

    fun u8(v: Int) = apply { out.write(v and 0xFF) }

    fun u16(v: Int) = apply {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    fun u32(v: Int) = apply {
        u16(v ushr 16)
        u16(v)
    }

    fun u64(v: Long) = apply {
        for (shift in 56 downTo 0 step 8) out.write((v ushr shift).toInt() and 0xFF)
    }

    fun str(s: String) = apply {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size <= 0xFFFF) { "string too long" }
        u16(b.size)
        out.write(b)
    }

    fun build(): ByteArray {
        val a = out.toByteArray()
        val len = a.size - 2
        a[0] = (len shr 8).toByte()
        a[1] = len.toByte()
        return a
    }
}

class Frame(val type: Int, private val data: ByteArray) {
    private var pos = 0

    /** True while there is payload left to read. Newer senders append fields that older ones leave out. */
    val hasMore: Boolean get() = pos < data.size

    fun u8(): Int {
        if (pos >= data.size) throw IOException("frame too short")
        return data[pos++].toInt() and 0xFF
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u32(): Int = (u16() shl 16) or u16()

    fun bytes(n: Int): ByteArray {
        if (pos + n > data.size) throw IOException("frame too short")
        return data.copyOfRange(pos, pos + n).also { pos += n }
    }

    /** Everything not read yet. */
    fun rest(): ByteArray = bytes(data.size - pos)

    fun str(): String {
        val n = u16()
        if (pos + n > data.size) throw IOException("string runs past the frame")
        val s = String(data, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }
}

/**
 * What the PC says is playing, from a MEDIA frame. [positionMs] was true when the frame was sent, and moves on at
 * [ratePercent] while [playing]. A [durationMs] of 0 means the PC doesn't know the length (a live stream, say).
 */
data class MediaState(
    val state: Int = Proto.MEDIA_NONE,
    val can: Int = 0,
    val volume: Int = 0,
    val muted: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val ratePercent: Int = 100,
    /** 0 for no artwork. Otherwise the picture arrives in MEDIA_ART frames carrying the same id. */
    val artId: Int = 0,
    val title: String = "",
    val artist: String = "",
) {
    /** Something is playing, or was a moment ago and can be resumed. */
    val showing: Boolean get() = state != Proto.MEDIA_NONE
    val playing: Boolean get() = state == Proto.MEDIA_PLAYING

    fun can(what: Int): Boolean = can and what != 0

    companion object {
        val NONE = MediaState()

        fun parse(f: Frame) = MediaState(
            state = f.u8(),
            can = f.u8(),
            volume = f.u8().coerceAtMost(100),
            muted = f.u8() != 0,
            positionMs = f.u32().toLong() and 0xFFFFFFFFL,
            durationMs = f.u32().toLong() and 0xFFFFFFFFL,
            ratePercent = f.u16(),
            artId = f.u32(),
            title = f.str(),
            artist = f.str(),
        )
    }
}

/** The PC's virtual desktops, from a DESKTOPS frame: how many there are and which one is showing (0 is the first). */
data class Desktops(val count: Int = 1, val current: Int = 0) {
    companion object {
        /** A PC with one desktop, or one too old to say. */
        val SINGLE = Desktops()

        fun parse(f: Frame): Desktops {
            val count = f.u8().coerceAtLeast(1)
            return Desktops(count, f.u8().coerceAtMost(count - 1))
        }
    }
}

/** Reads one frame, or returns null on a clean end of stream between frames. */
fun readFrame(input: InputStream): Frame? {
    val first = input.read()
    if (first < 0) return null
    val second = input.read()
    if (second < 0) throw EOFException()
    val len = (first shl 8) or second
    if (len < 1 || len > Proto.MAX_FRAME) throw IOException("bad frame length $len")
    val body = ByteArray(len)
    var off = 0
    while (off < len) {
        val r = input.read(body, off, len - off)
        if (r < 0) throw EOFException()
        off += r
    }
    return Frame(body[0].toInt() and 0xFF, body.copyOfRange(1, len))
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** The short code both screens show while pairing; it matches only if nobody sits between phone and PC. */
fun verifyCode(fingerprint: String): String {
    val s = fingerprint.take(8).uppercase()
    return if (s.length == 8) "${s.take(4)} ${s.drop(4)}" else s
}
