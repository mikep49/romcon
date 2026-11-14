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
    const val DISCOVERY_REQUEST = "ROMCON?1"
    const val DISCOVERY_REPLY = "ROMCON!1"
    const val MAX_FRAME = 8192

    // Phone -> PC
    const val HELLO = 0x01
    const val PING = 0x02
    const val MOUSE_MOVE = 0x10
    const val MOUSE_BUTTON = 0x11
    const val SCROLL = 0x12
    const val TEXT = 0x20
    const val KEY = 0x21
    const val CHAR_WITH_MODS = 0x22
    const val COMMAND = 0x30

    // PC -> phone
    const val WELCOME = 0x81
    const val REJECTED = 0x82
    const val PONG = 0x83
    const val STATUS = 0x84

    const val AUTH_TOKEN = 0
    const val AUTH_PIN = 1

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

    fun u8(): Int {
        if (pos >= data.size) throw IOException("frame too short")
        return data[pos++].toInt() and 0xFF
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun str(): String {
        val n = u16()
        if (pos + n > data.size) throw IOException("string runs past the frame")
        val s = String(data, pos, n, Charsets.UTF_8)
        pos += n
        return s
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
