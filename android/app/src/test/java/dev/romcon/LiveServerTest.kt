package dev.romcon

import dev.romcon.net.Discovery
import dev.romcon.net.FailKind
import dev.romcon.net.MediaState
import dev.romcon.net.Proto
import dev.romcon.net.RemoteException
import dev.romcon.net.RemoteSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Talks to a real RomCon PC with the app's own networking code. Skipped unless pointed at one:
 *
 *   RomCon.exe --dry-run --data-dir <dir> --port 50599     (dry-run logs input instead of sending it)
 *   ROMCON_TEST_HOST=127.0.0.1 ROMCON_TEST_PORT=50599 ROMCON_TEST_PIN=<pin from the window> gradlew test
 */
class LiveServerTest {
    private val host = System.getenv("ROMCON_TEST_HOST")
    private val port = System.getenv("ROMCON_TEST_PORT")?.toIntOrNull() ?: Proto.DEFAULT_PORT
    private val pin = System.getenv("ROMCON_TEST_PIN")

    /** Run against a PC on a fallback port: `RomCon.exe --dry-run --data-dir <dir> --port 45505`. */
    @Test
    fun findsPcThatMovedToFallbackPort() {
        assumeTrue("set ROMCON_TEST_HOST and a ROMCON_TEST_PORT from Proto.FALLBACK_PORTS to run", host != null && port in Proto.FALLBACK_PORTS)
        val fingerprint = RemoteSession.open(host!!, port, null).use { it.fingerprint }
        val nothingThere = 1

        // Saved with a port the PC no longer listens on: found on the fallback port, with the same certificate.
        RemoteSession.openPaired(host, nothingThere, fingerprint).use {
            assertEquals(port, it.port)
            assertEquals(fingerprint, it.fingerprint)
        }

        // Any other certificate on a fallback port doesn't pass for the PC, and the failure reported is the saved port's.
        try {
            RemoteSession.openPaired(host, nothingThere, "0".repeat(64)).close()
            fail("connected to a PC with another certificate")
        } catch (e: RemoteException) {
            assertEquals(FailKind.UNREACHABLE, e.kind)
        }
    }

    @Test
    fun pairsConnectsAndSendsInput() {
        assumeTrue("set ROMCON_TEST_HOST and ROMCON_TEST_PIN to run", host != null && pin != null)

        // Discovery answers with the same certificate the TLS connection presents.
        val probe = RemoteSession.open(host!!, port, null)
        val fingerprint = probe.fingerprint
        probe.close()
        if (host == "127.0.0.1") {
            val found = Discovery.scan()
            assertTrue("discovery found the PC: $found", found.any { it.fingerprint == fingerprint && it.port == port })
        }

        // An unknown token is refused.
        try {
            RemoteSession.open(host, port, fingerprint).hello("jvm-test", "JVM test", Proto.AUTH_TOKEN, "nope")
            fail("bogus token accepted")
        } catch (e: RemoteException) {
            assertEquals(FailKind.NOT_PAIRED, e.kind)
        }

        // Pair with the PIN, then sign in again with the issued token.
        val paired = RemoteSession.open(host, port, fingerprint)
        val welcome = paired.hello("jvm-test", "JVM test", Proto.AUTH_PIN, pin!!)
        assertTrue(welcome.token.isNotEmpty())
        paired.close()

        val session = RemoteSession.open(host, port, fingerprint)
        session.hello("jvm-test", "JVM test", Proto.AUTH_TOKEN, welcome.token)
        val status = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val udp = CountDownLatch(1)
        session.start(object : RemoteSession.Listener {
            override fun onStatus(pcPaused: Boolean) = status.countDown()
            override fun onUdp(active: Boolean) {
                if (active) udp.countDown()
            }
            override fun onClosed(error: RemoteException) = closed.countDown()
        })
        assertTrue("PC sent its status", status.await(3, TimeUnit.SECONDS))
        repeat(50) { session.move(2, -1) }
        session.scroll(-240, 0)
        session.button(Proto.BUTTON_LEFT, true)
        session.button(Proto.BUTTON_LEFT, false)
        session.text("hello from the JVM\n")
        session.key(0x43, Proto.KEY_TAP, Proto.MOD_CTRL)
        Thread.sleep(3500) // long enough for a ping round trip
        assertTrue("connection stayed up", session.isOpen && closed.count == 1L)

        // Switch pointer movement to UDP mid-connection. The PC only confirms a datagram it could decrypt.
        session.preferUdp = true
        assertTrue("PC confirmed a UDP datagram", udp.await(5, TimeUnit.SECONDS))
        // Drags: in the PC's log each movement must sit between its button down and up, though it took another road.
        repeat(40) { i ->
            session.button(Proto.BUTTON_LEFT, true)
            repeat(20) { session.move(3, 4) }
            session.button(Proto.BUTTON_LEFT, false)
            if (i % 4 == 0) Thread.sleep(15)
        }
        session.scroll(120, 0)
        Thread.sleep(500)
        assertTrue("connection stayed up over UDP", session.isOpen && closed.count == 1L)
        session.close()

        // A media link: the PC says what's playing straight away, and takes media commands and nothing else.
        val link = RemoteSession.open(host, port, fingerprint)
        assertTrue("PC offers media links", link.hello("jvm-test", "JVM test", Proto.AUTH_TOKEN, welcome.token, Proto.ROLE_MEDIA).media)
        val states = LinkedBlockingQueue<MediaState>()
        val linkClosed = CountDownLatch(1)
        link.start(
            object : RemoteSession.Listener {
                override fun onStatus(pcPaused: Boolean) = Unit
                override fun onMedia(media: MediaState) {
                    states.add(media)
                }
                override fun onClosed(error: RemoteException) = linkClosed.countDown()
            },
            relaxed = true,
        )
        assertNotNull("PC said what's playing", states.poll(3, TimeUnit.SECONDS))
        link.mediaCommand(Proto.MEDIA_ADJUST_VOLUME, -2) // negative, to check the sign survives; dry-run logs "volume -2"
        link.move(40, 40) // ignored: the PC's log must show no movement after the volume command
        val before = link.lastHeard
        link.ping()
        Thread.sleep(500)
        assertTrue("PC answered a ping on the media link", link.lastHeard > before)
        assertTrue("media link stayed up", link.isOpen && linkClosed.count == 1L)
        link.close()

        // A different pinned certificate is refused before anything is sent.
        try {
            RemoteSession.open(host, port, "0".repeat(64))
            fail("wrong certificate accepted")
        } catch (e: RemoteException) {
            assertEquals(FailKind.IDENTITY_CHANGED, e.kind)
        }
    }
}
