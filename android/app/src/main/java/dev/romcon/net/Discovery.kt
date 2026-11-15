package dev.romcon.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class DiscoveredPc(val name: String, val host: String, val port: Int, val fingerprint: String)

/** True for an IPv4 address in a home or office range, which only means something on that network. */
fun isPrivateLanIp(host: String): Boolean {
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168)
}

/** Finds RomCon PCs on the local network with a UDP broadcast. Tailscale doesn't carry broadcasts; add those PCs by address. */
object Discovery {

    /** Blocking; run off the main thread. */
    fun scan(timeoutMs: Long = 1200): List<DiscoveredPc> {
        val found = LinkedHashMap<String, DiscoveredPc>()
        val request = Proto.DISCOVERY_REQUEST.toByteArray(Charsets.UTF_8)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 150
            val targets = broadcastTargets()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            var sends = 0
            var nextSend = 0L
            val buffer = ByteArray(1024)
            while (System.nanoTime() < deadline) {
                val now = System.nanoTime()
                // Broadcasts are easily dropped on Wi-Fi, so ask a few times.
                if (sends < 3 && now >= nextSend) {
                    for (target in targets) {
                        try {
                            socket.send(DatagramPacket(request, request.size, target, Proto.DISCOVERY_PORT))
                        } catch (_: Exception) {
                        }
                    }
                    sends++
                    nextSend = now + 350_000_000
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                parse(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address)?.let { found[it.fingerprint] = it }
            }
        }
        return found.values.toList()
    }

    private fun parse(reply: String, from: InetAddress): DiscoveredPc? {
        val parts = reply.split('\n')
        if (parts.size < 4 || parts[0] != Proto.DISCOVERY_REPLY) return null
        val port = parts[2].toIntOrNull() ?: return null
        val fingerprint = parts[3].trim().lowercase()
        if (fingerprint.length != 64) return null
        val host = from.hostAddress ?: return null
        return DiscoveredPc(parts[1].ifBlank { host }, host, port, fingerprint)
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address }
                .mapNotNullTo(targets) { it.broadcast }
        } catch (_: Exception) {
        }
        return targets.toList()
    }
}
