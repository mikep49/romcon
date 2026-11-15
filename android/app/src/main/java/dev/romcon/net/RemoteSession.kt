package dev.romcon.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

enum class FailKind { UNREACHABLE, IDENTITY_CHANGED, NOT_PAIRED, WRONG_PIN, PAIRING_CLOSED, TOO_MANY_ATTEMPTS, VERSION, PROTOCOL, CLOSED }

class RemoteException(val kind: FailKind, message: String, cause: Throwable? = null) : Exception(message, cause)

data class Welcome(val serverName: String, val token: String, val features: Int = 0) {
    /** The PC can say what's playing on it (see [Proto.ROLE_MEDIA]). */
    val media: Boolean get() = features and Proto.FEATURE_MEDIA != 0
}

/**
 * One TLS connection to a RomCon PC. Blocking calls: run [open] and [hello] off the main thread.
 * After [start], input calls only queue messages; a writer thread sends them, merging mouse moves
 * when the network falls behind, and pings so both ends notice a dead link.
 *
 * With [preferUdp] on, pointer movement and scrolling leave as datagrams through a [PointerChannel] once
 * the PC has shown that it receives them. Everything else, and all of it until then, uses the TLS connection.
 *
 * Signed in with [Proto.ROLE_MEDIA], the connection is a media link instead: the PC reports what's playing
 * ([Listener.onMedia]) and takes [mediaCommand], and nothing else. Start that one `relaxed`, since it lives
 * in the background, where frequent pings would keep the phone awake.
 */
class RemoteSession private constructor(private val socket: SSLSocket, private val address: InetAddress, val fingerprint: String) : Closeable {

    interface Listener {
        fun onStatus(pcPaused: Boolean)

        /** Pointer movement switched to UDP ([active]) or back to the TCP connection. */
        fun onUdp(active: Boolean) {}

        /** On a media link: what's playing on the PC changed. */
        fun onMedia(media: MediaState) {}

        /** On a media link: the JPEG artwork for the [MediaState] with this [artId] arrived. */
        fun onArt(artId: Int, jpeg: ByteArray) {}

        /** The connection died on its own (not through [close]). */
        fun onClosed(error: RemoteException)
    }

    private sealed interface Out {
        class Move(var dx: Int, var dy: Int) : Out
        class Scroll(var vertical: Int, var horizontal: Int) : Out
        class Raw(val bytes: ByteArray) : Out

        /** Nothing to send; gets the writer to look at the UDP state again. */
        data object Wake : Out
    }

    private val input = BufferedInputStream(socket.inputStream)
    private val queue = LinkedBlockingQueue<Out>()
    private val closed = AtomicBoolean(false)
    private var listener: Listener? = null

    private var pingIntervalMs = PING_INTERVAL_MS
    private var readTimeoutMs = READ_TIMEOUT_MS

    /** When the PC was last heard from, in [System.nanoTime]. */
    @Volatile var lastHeard = System.nanoTime()
        private set

    // Artwork being put together from MEDIA_ART frames. Only the reader thread uses these.
    private var artId = 0
    private var artNext = -1
    private val art = ByteArrayOutputStream()

    @Volatile private var pointer: PointerChannel? = null
    private val udpRequested = AtomicBoolean(false)
    @Volatile private var udpActive = false

    /** When the PC last confirmed a datagram, in [System.nanoTime]; 0 for never. */
    @Volatile private var lastUdpPong = 0L
    @Volatile private var lastUdpProbe = 0L

    /** TCP frames written since HELLO. The PC counts them too, to put datagrams in order with them. */
    @Volatile private var tcpFrames = 0

    /** Datagrams have gone out since the last UDP_SYNC. Only the writer thread uses it. */
    private var unsynced = false

    /** Send pointer movement and scrolling over UDP when the PC can take it. Safe to change while connected. */
    @Volatile var preferUdp = false
        set(value) {
            field = value
            if (value) {
                if (udpRequested.compareAndSet(false, true)) raw(FrameBuilder(Proto.UDP_REQUEST))
                lastUdpProbe = 0L
            }
            offer(Out.Wake)
        }

    val isOpen: Boolean get() = !closed.get()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val HELLO_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 12000
        private const val PING_INTERVAL_MS = 2500L
        private const val PING_INTERVAL_NS = PING_INTERVAL_MS * 1_000_000
        private const val RELAXED_PING_INTERVAL_MS = 20_000L
        private const val RELAXED_READ_TIMEOUT_MS = 50_000

        /** With no confirmed datagram for this long, pointer movement goes back to TCP. */
        private const val UDP_DEAD_NS = 3 * PING_INTERVAL_NS
        private const val TEXT_CHUNK = 1000
        private val PING = FrameBuilder(Proto.PING).build()

        /**
         * Connects and runs the TLS handshake. With [expectedFingerprint] set, a PC presenting any other
         * certificate is refused before anything secret is sent.
         */
        fun open(host: String, port: Int, expectedFingerprint: String?): RemoteSession {
            val raw = Socket()
            val trust = CapturingTrustManager(expectedFingerprint)
            try {
                raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                raw.tcpNoDelay = true
                raw.keepAlive = true
                val context = SSLContext.getInstance("TLS")
                context.init(null, arrayOf(trust), SecureRandom())
                val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                ssl.soTimeout = HELLO_TIMEOUT_MS
                ssl.startHandshake()
                return RemoteSession(ssl, raw.inetAddress, trust.seen ?: throw RemoteException(FailKind.PROTOCOL, "The PC sent no certificate."))
            } catch (e: RemoteException) {
                raw.closeQuietly()
                throw e
            } catch (e: SSLException) {
                raw.closeQuietly()
                if (trust.mismatch) {
                    throw RemoteException(
                        FailKind.IDENTITY_CHANGED,
                        "This PC's identity changed since you paired. If you reinstalled RomCon on it, pair again; otherwise someone may be impersonating it.",
                        e,
                    )
                }
                throw RemoteException(FailKind.UNREACHABLE, "Secure connection failed: ${e.message}", e)
            } catch (e: UnknownHostException) {
                raw.closeQuietly()
                throw RemoteException(FailKind.UNREACHABLE, "Can't find \"$host\". Check the address.", e)
            } catch (e: SocketTimeoutException) {
                raw.closeQuietly()
                throw RemoteException(FailKind.UNREACHABLE, "No answer from $host. Is the PC on and on the same network or Tailscale?", e)
            } catch (e: ConnectException) {
                raw.closeQuietly()
                throw RemoteException(FailKind.UNREACHABLE, "$host refused the connection. Is RomCon running on the PC?", e)
            } catch (e: NoRouteToHostException) {
                raw.closeQuietly()
                throw RemoteException(FailKind.UNREACHABLE, "No route to $host. Check your Wi-Fi or Tailscale connection.", e)
            } catch (e: IOException) {
                raw.closeQuietly()
                throw RemoteException(FailKind.UNREACHABLE, "Couldn't reach $host: ${e.message}", e)
            }
        }

        private fun Socket.closeQuietly() = try {
            close()
        } catch (_: IOException) {
        }
    }

    /**
     * Signs in with a pairing token ([Proto.AUTH_TOKEN]) or a PIN ([Proto.AUTH_PIN]). Closes the session on failure.
     * [role] says what the connection is for; a PC from before media links ignores it and takes it for control.
     */
    fun hello(deviceId: String, deviceName: String, mode: Int, secret: String, role: Int = Proto.ROLE_CONTROL): Welcome {
        try {
            val frame = FrameBuilder(Proto.HELLO).u8(Proto.VERSION).str(deviceId).str(deviceName).u8(mode).str(secret).u8(role).build()
            socket.outputStream.write(frame)
            socket.outputStream.flush()
            val reply = readFrame(input) ?: throw RemoteException(FailKind.CLOSED, "The PC closed the connection.")
            return when (reply.type) {
                Proto.WELCOME -> Welcome(reply.str(), reply.str(), if (reply.hasMore) reply.u8() else 0)
                Proto.REJECTED -> {
                    val code = reply.u8()
                    val message = reply.str()
                    val kind = when (code) {
                        Proto.REJECT_VERSION -> FailKind.VERSION
                        Proto.REJECT_NOT_PAIRED -> FailKind.NOT_PAIRED
                        Proto.REJECT_WRONG_PIN -> FailKind.WRONG_PIN
                        Proto.REJECT_PAIRING_CLOSED -> FailKind.PAIRING_CLOSED
                        Proto.REJECT_TOO_MANY_ATTEMPTS -> FailKind.TOO_MANY_ATTEMPTS
                        else -> FailKind.PROTOCOL
                    }
                    throw RemoteException(kind, message)
                }
                else -> throw RemoteException(FailKind.PROTOCOL, "Unexpected reply from the PC.")
            }
        } catch (e: RemoteException) {
            close()
            throw e
        } catch (e: IOException) {
            close()
            throw RemoteException(FailKind.CLOSED, "Lost the connection while signing in.", e)
        }
    }

    /**
     * Starts the reader and writer threads. Call once, after a successful [hello]. A [relaxed] session pings
     * rarely and waits longer for an answer; use [ping] and [lastHeard] to check on it sooner.
     */
    fun start(listener: Listener, relaxed: Boolean = false) {
        this.listener = listener
        if (relaxed) {
            pingIntervalMs = RELAXED_PING_INTERVAL_MS
            readTimeoutMs = RELAXED_READ_TIMEOUT_MS
        }
        lastHeard = System.nanoTime()
        Thread(::writerLoop, "romcon-writer").apply { isDaemon = true }.start()
        Thread(::readerLoop, "romcon-reader").apply { isDaemon = true }.start()
    }

    fun move(dx: Int, dy: Int) {
        if (dx != 0 || dy != 0) offer(Out.Move(dx, dy))
    }

    fun scroll(vertical: Int, horizontal: Int) {
        if (vertical != 0 || horizontal != 0) offer(Out.Scroll(vertical, horizontal))
    }

    fun button(button: Int, down: Boolean) = raw(FrameBuilder(Proto.MOUSE_BUTTON).u8(button).u8(if (down) 1 else 0))

    fun key(vk: Int, action: Int, mods: Int = 0) = raw(FrameBuilder(Proto.KEY).u16(vk).u8(action).u8(mods))

    fun charWithMods(c: Char, mods: Int) = raw(FrameBuilder(Proto.CHAR_WITH_MODS).u8(mods).u16(c.code))

    fun command(command: Int) = raw(FrameBuilder(Proto.COMMAND).u8(command))

    /** One of [Proto.MEDIA_PLAY] and its neighbours. The PC only carries these out on a media link. */
    fun mediaCommand(command: Int, value: Int = 0) = raw(FrameBuilder(Proto.MEDIA_COMMAND).u8(command).u32(value))

    /** Asks the PC to answer now; [lastHeard] moves on when it does. */
    fun ping() = raw(FrameBuilder(Proto.PING))

    fun text(text: String) {
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + TEXT_CHUNK)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end-- // keep emoji pairs together
            raw(FrameBuilder(Proto.TEXT).str(text.substring(start, end)))
            start = end
        }
    }

    /** Safe to call from any thread, including the main thread. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            queue.clear()
            // Closing a TLS socket sends a close_notify alert. That is network I/O, which Android
            // forbids on the main thread (NetworkOnMainThreadException), so it happens off the caller's thread.
            Thread({
                pointer?.close()
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }, "romcon-close").apply { isDaemon = true }.start()
        }
    }

    private fun raw(builder: FrameBuilder) = offer(Out.Raw(builder.build()))

    private fun offer(out: Out) {
        if (!closed.get()) queue.offer(out)
    }

    private fun fail(e: Throwable) {
        if (closed.compareAndSet(false, true)) {
            pointer?.close()
            try {
                socket.close()
            } catch (_: IOException) {
            }
            val error = e as? RemoteException ?: RemoteException(FailKind.CLOSED, "Connection to the PC was lost.", e)
            listener?.onClosed(error)
        }
    }

    private fun writerLoop() {
        val out = BufferedOutputStream(socket.outputStream, 4096)
        val batch = ArrayList<Out>(64)
        var lastPing = 0L
        try {
            while (!closed.get()) {
                batch.clear()
                val first = queue.poll(pingIntervalMs, TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch.add(first)
                    queue.drainTo(batch, 255)
                }
                writeBatch(out, batch)
                val now = System.nanoTime()
                if (now - lastPing >= pingIntervalMs * 1_000_000) {
                    write(out, PING)
                    lastPing = now
                }
                out.flush()
                val channel = pointer
                if (channel != null && preferUdp && now - lastUdpProbe >= PING_INTERVAL_NS) {
                    probe(channel)
                    lastUdpProbe = now
                }
                refreshUdp()
            }
        } catch (_: InterruptedException) {
            fail(EOFException())
        } catch (e: IOException) {
            fail(e)
        }
    }

    /** Writes queued messages in order, merging back-to-back moves and back-to-back scrolls. */
    private fun writeBatch(out: OutputStream, batch: List<Out>) {
        var moveX = 0
        var moveY = 0
        var scrollV = 0
        var scrollH = 0
        fun flushMove() {
            writeSplit(out, Proto.MOUSE_MOVE, moveX, moveY)
            moveX = 0
            moveY = 0
        }
        fun flushScroll() {
            writeSplit(out, Proto.SCROLL, scrollV, scrollH)
            scrollV = 0
            scrollH = 0
        }
        for (item in batch) {
            when (item) {
                is Out.Move -> {
                    flushScroll()
                    moveX += item.dx
                    moveY += item.dy
                }
                is Out.Scroll -> {
                    flushMove()
                    scrollV += item.vertical
                    scrollH += item.horizontal
                }
                is Out.Raw -> {
                    flushMove()
                    flushScroll()
                    syncUdp(out)
                    write(out, item.bytes)
                }
                Out.Wake -> Unit
            }
        }
        flushMove()
        flushScroll()
    }

    /** Sends a pair of signed 16-bit values, split into several frames if they overflow. */
    private fun writeSplit(out: OutputStream, type: Int, a: Int, b: Int) {
        var x = a
        var y = b
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-32767, 32767)
            val sy = y.coerceIn(-32767, 32767)
            writePointer(out, FrameBuilder(type).u16(sx).u16(sy).build())
            x -= sx
            y -= sy
        }
    }

    private fun write(out: OutputStream, frame: ByteArray) {
        out.write(frame)
        tcpFrames++
    }

    /** A movement or scroll frame: by UDP while that works, otherwise over TCP like everything else. */
    private fun writePointer(out: OutputStream, frame: ByteArray) {
        val channel = pointer
        if (channel != null && udpActive) {
            // The PC holds a datagram back until it has the TCP frames written before it, so get those moving first.
            out.flush()
            try {
                channel.send(tcpFrames, frame)
                unsynced = true
                return
            } catch (_: IOException) {
                lastUdpPong = 0L
                refreshUdp()
            }
        }
        write(out, frame)
    }

    /**
     * Before a click, key or text that follows movement sent by UDP: names the last datagram, so the PC can
     * apply that movement first even if the TCP frame gets there ahead of it.
     */
    private fun syncUdp(out: OutputStream) {
        val channel = pointer ?: return
        if (!unsynced) return
        unsynced = false
        write(out, FrameBuilder(Proto.UDP_SYNC).u64(channel.sent).build())
    }

    /** The PC answers a datagram ping with UDP_PONG over TCP, which is how the phone knows datagrams arrive. */
    private fun probe(channel: PointerChannel) {
        try {
            channel.send(tcpFrames, PING)
        } catch (_: IOException) {
        }
    }

    private fun openPointerChannel(offer: Frame) {
        val port = offer.u16()
        val id = offer.u32()
        val key = offer.bytes(Proto.UDP_KEY_SIZE)
        if (pointer != null) return
        val channel = try {
            PointerChannel(address, port, id, key)
        } catch (_: IOException) {
            return
        }
        pointer = channel
        if (closed.get()) {
            channel.close()
            return
        }
        // Twice, in case the network drops the first one; the writer keeps probing after that.
        probe(channel)
        probe(channel)
    }

    /** Works out whether pointer movement should use UDP right now and tells the listener when that changes. */
    @Synchronized
    private fun refreshUdp() {
        val pong = lastUdpPong
        val active = preferUdp && pointer != null && pong != 0L && System.nanoTime() - pong < UDP_DEAD_NS
        if (active != udpActive) {
            udpActive = active
            listener?.onUdp(active)
        }
    }

    private fun readerLoop() {
        try {
            socket.soTimeout = readTimeoutMs
            while (!closed.get()) {
                val frame = readFrame(input) ?: throw RemoteException(FailKind.CLOSED, "The PC closed the connection.")
                lastHeard = System.nanoTime()
                when (frame.type) {
                    Proto.STATUS -> listener?.onStatus(frame.u8() != 0)
                    Proto.PONG -> Unit
                    Proto.UDP_OFFER -> openPointerChannel(frame)
                    Proto.UDP_PONG -> {
                        lastUdpPong = System.nanoTime()
                        refreshUdp()
                    }
                    Proto.MEDIA -> listener?.onMedia(MediaState.parse(frame))
                    Proto.MEDIA_ART -> collectArt(frame)
                }
            }
        } catch (e: SocketTimeoutException) {
            fail(RemoteException(FailKind.CLOSED, "The PC stopped responding.", e))
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Artwork arrives as numbered pieces, in order. A piece out of turn belongs to a picture that was superseded. */
    private fun collectArt(frame: Frame) {
        val id = frame.u32()
        val index = frame.u16()
        val count = frame.u16()
        if (index == 0) {
            artId = id
            artNext = 0
            art.reset()
        }
        if (id != artId || index != artNext) {
            artNext = -1
            return
        }
        art.write(frame.rest())
        artNext++
        if (art.size() > Proto.MAX_ART_BYTES) {
            artNext = -1
            art.reset()
        } else if (artNext == count) {
            listener?.onArt(id, art.toByteArray())
            artNext = -1
            art.reset()
        }
    }

    /** Accepts the PC's self-signed certificate, records its fingerprint, and rejects it if it isn't the pinned one. */
    private class CapturingTrustManager(private val expected: String?) : X509TrustManager {
        @Volatile var seen: String? = null
        @Volatile var mismatch = false

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val fp = sha256Hex(leaf.encoded)
            seen = fp
            if (expected != null && !expected.equals(fp, ignoreCase = true)) {
                mismatch = true
                throw CertificateException("certificate fingerprint changed")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("not a server")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
