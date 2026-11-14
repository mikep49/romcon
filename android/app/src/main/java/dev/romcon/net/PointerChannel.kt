package dev.romcon.net

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The UDP side of a connection. Pointer movement and scrolling go to the PC as AES-256-GCM datagrams, so a
 * packet the network drops costs one movement instead of holding up everything behind it. The PC hands out
 * [channelId] and [key] over TLS, and only ever answers over TLS, so nothing is read from this socket.
 */
internal class PointerChannel(address: InetAddress, port: Int, private val channelId: Int, key: ByteArray) : Closeable {
    private val socket = DatagramSocket()
    private val key = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var counter = 0L

    /** The counter of the last datagram sent. */
    val sent: Long
        @Synchronized get() = counter

    init {
        // Connected, so a PC that has stopped listening shows up as an error on a later send.
        socket.connect(address, port)
    }

    /** Sends [frames], which the phone produced after writing [tcpFrames] frames to the TCP connection. */
    @Synchronized
    fun send(tcpFrames: Int, frames: ByteArray) {
        val packet = sealDatagram(cipher, key, channelId, ++counter, tcpFrames, frames)
        socket.send(DatagramPacket(packet, packet.size))
    }

    override fun close() = socket.close()
}

/**
 * Builds one datagram. The channel id and [counter] are the GCM nonce, so a counter must never repeat under
 * one key. The sealed part is [tcpFrames] as a u32 followed by [frames].
 */
internal fun sealDatagram(cipher: Cipher, key: SecretKey, channelId: Int, counter: Long, tcpFrames: Int, frames: ByteArray): ByteArray {
    val nonce = ByteBuffer.allocate(Proto.UDP_NONCE_SIZE).putInt(channelId).putLong(counter).array()
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(Proto.UDP_TAG_BITS, nonce))
    val body = ByteBuffer.allocate(4 + frames.size).putInt(tcpFrames).put(frames).array()
    return nonce + cipher.doFinal(body)
}
