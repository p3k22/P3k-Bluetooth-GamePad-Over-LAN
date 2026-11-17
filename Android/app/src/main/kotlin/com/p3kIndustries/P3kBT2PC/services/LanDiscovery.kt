package com.p3kIndustries.P3kBT2PC.services

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

private const val DISCOVERY_PORT = 8888
private const val DEFAULT_INPUT_PORT = 8080
private const val RECEIVE_TIMEOUT_MS = 250
private const val SEARCH_DURATION_NS = 8_000_000_000L
private const val PROBE_INTERVAL_NS = 1_000_000_000L
private const val RECEIVE_BUFFER_SIZE = 2048
private const val PROBE_MESSAGE = "P3K_GAMEPAD_DISCOVER_V2"
private const val LEGACY_ANNOUNCEMENT = "DISCOVER_P3KBTPASSTHROUGH_CLIENT"
private const val ANNOUNCEMENT_TYPE = "p3k-gamepad-server"

data class Destination(val host: String, val port: Int, val name: String)

/** Broadcasts a probe on the LAN and reports every Windows app that answers or announces itself. */
class LanDiscovery {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var generation = 0

    fun cancel() {
        generation++
        socket?.close()
        socket = null
    }

    /**
     * Searches on a background thread; [found] is called once per distinct address,
     * [completed] when the search ends.
     */
    fun discover(found: (Destination) -> Unit, completed: () -> Unit) {
        cancel()
        val searchGeneration = generation
        thread(name = "P3k-discovery") {
            var discoverySocket: DatagramSocket? = null
            try {
                discoverySocket = DatagramSocket(null)
                bindToDiscoveryPort(discoverySocket)
                if (searchGeneration != generation) return@thread
                socket = discoverySocket
                listenForServers(discoverySocket, searchGeneration, found)
            } catch (_: Exception) {
                // A failed search simply ends without results.
            } finally {
                discoverySocket?.close()
                if (searchGeneration == generation) {
                    socket = null
                    completed()
                }
            }
        }
    }

    private fun bindToDiscoveryPort(discoverySocket: DatagramSocket) {
        discoverySocket.reuseAddress = true
        discoverySocket.broadcast = true
        discoverySocket.soTimeout = RECEIVE_TIMEOUT_MS
        discoverySocket.bind(InetSocketAddress(DISCOVERY_PORT))
    }

    private fun listenForServers(discoverySocket: DatagramSocket, searchGeneration: Int, found: (Destination) -> Unit) {
        val targets = broadcastTargets()
        val seenAddresses = mutableSetOf<String>()
        val startedNs = System.nanoTime()
        var lastProbeNs = 0L
        while (searchGeneration == generation && (System.nanoTime() - startedNs) < SEARCH_DURATION_NS) {
            if (System.nanoTime() - lastProbeNs > PROBE_INTERVAL_NS) {
                lastProbeNs = System.nanoTime()
                sendProbe(discoverySocket, targets)
            }
            val destination = receiveAnnouncement(discoverySocket) ?: continue
            val isUsablePort = destination.port in 1..65535
            if (!isUsablePort || !seenAddresses.add("${destination.host}:${destination.port}")) continue
            if (searchGeneration == generation) found(destination)
        }
    }

    private fun broadcastTargets(): Set<InetAddress> {
        val targets = mutableSetOf(InetAddress.getByName("255.255.255.255"))
        val activeInterfaces = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        activeInterfaces.forEach { networkInterface ->
            networkInterface.interfaceAddresses
                .filter { it.address is Inet4Address }
                .mapNotNullTo(targets) { it.broadcast }
        }
        return targets
    }

    private fun sendProbe(discoverySocket: DatagramSocket, targets: Set<InetAddress>) {
        val probe = PROBE_MESSAGE.toByteArray()
        targets.forEach { address ->
            try {
                discoverySocket.send(DatagramPacket(probe, probe.size, address, DISCOVERY_PORT))
            } catch (_: Exception) {
                // One unreachable interface must not stop the probe on the others.
            }
        }
    }

    /** Returns null on timeout and for datagrams that are not a Windows app announcement. */
    private fun receiveAnnouncement(discoverySocket: DatagramSocket): Destination? {
        val packet = DatagramPacket(ByteArray(RECEIVE_BUFFER_SIZE), RECEIVE_BUFFER_SIZE)
        try {
            discoverySocket.receive(packet)
        } catch (_: SocketTimeoutException) {
            return null
        }
        val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
        val senderIp = packet.address.hostAddress ?: return null
        return parseAnnouncement(message, senderIp)
    }

    private fun parseAnnouncement(message: String, senderIp: String): Destination? {
        if (message == LEGACY_ANNOUNCEMENT) return Destination(senderIp, DEFAULT_INPUT_PORT, senderIp)
        val announcement = try {
            JSONObject(message)
        } catch (_: Exception) {
            return null
        }
        if (announcement.optString("type") != ANNOUNCEMENT_TYPE) return null
        val port = announcement.optInt("port", DEFAULT_INPUT_PORT)
        val name = announcement.optString("name", senderIp)
        return Destination(senderIp, port, name)
    }
}
