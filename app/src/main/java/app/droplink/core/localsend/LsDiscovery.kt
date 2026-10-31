package app.droplink.core.localsend

import app.droplink.core.protocol.Json
import app.droplink.core.transfer.CoreLog
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LocalSend discovery: multicast UDP announcements on 224.0.0.167:53317, answered with an HTTP
 * register call (or a UDP reply). When multicast is blocked by the router or ROM, [scanSubnets]
 * falls back to asking every address in the local /24 ("legacy" mode in the spec).
 */
class LsDiscovery(
    private val selfInfo: () -> LsDevice,
    private val client: LsClient,
    private val onPeer: (LsDevice) -> Unit,
    private val port: Int = LocalSend.PORT,
    private val group: String = LocalSend.MULTICAST_GROUP,
) {
    @Volatile private var socket: MulticastSocket? = null
    private val workers = Executors.newFixedThreadPool(4) { r -> Thread(r, "ls-disc").apply { isDaemon = true } }

    @Synchronized
    fun start() {
        if (socket?.isClosed == false) return
        val s = MulticastSocket(null as java.net.SocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
            timeToLive = 4
        }
        val groupAddr = InetSocketAddress(InetAddress.getByName(group), port)
        var joined = 0
        for (nif in interfaces()) {
            if (runCatching { s.joinGroup(groupAddr, nif) }.isSuccess) joined++
        }
        if (joined == 0) runCatching { s.joinGroup(groupAddr, null) }
        socket = s
        Thread({ receiveLoop(s) }, "ls-multicast").apply { isDaemon = true }.start()
        CoreLog.log("LocalSend discovery listening on $group:$port ($joined interfaces)")
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    /** Tells everyone on the network that we're here; they answer with their info. */
    fun announce() {
        val data = Json.stringify(selfInfo().toJson(announce = true)).toByteArray(Charsets.UTF_8)
        sendToGroup(data)
    }

    private fun sendToGroup(data: ByteArray) {
        val s = socket ?: return
        val target = InetSocketAddress(InetAddress.getByName(group), port)
        val nifs = interfaces()
        if (nifs.isEmpty()) {
            runCatching { s.send(DatagramPacket(data, data.size, target)) }
            return
        }
        for (nif in nifs) {
            runCatching {
                synchronized(s) {
                    s.networkInterface = nif
                    s.send(DatagramPacket(data, data.size, target))
                }
            }
        }
    }

    private fun receiveLoop(s: MulticastSocket) {
        val buf = ByteArray(8 * 1024)
        while (!s.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val text = String(p.data, p.offset, p.length, Charsets.UTF_8)
                handle(text, p.address.hostAddress ?: continue)
            } catch (e: Exception) {
                if (s.isClosed) return
            }
        }
    }

    /** Handles one discovery message (visible for tests). */
    fun handle(text: String, fromIp: String) {
        val m = runCatching { Json.parseObject(text) }.getOrNull() ?: return
        val device = runCatching { LsDevice.fromJson(m, fromIp) }.getOrNull() ?: return
        if (device.fingerprint == selfInfo().fingerprint) return // our own announcement
        onPeer(device)
        if (m["announce"] == true || m["announcement"] == true) {
            workers.execute {
                // Preferred answer: HTTP register (gives them our full info); fallback: UDP reply.
                val ok = client.register(fromIp, device.port, device.https) != null
                if (!ok) sendToGroup(Json.stringify(selfInfo().toJson(announce = false)).toByteArray(Charsets.UTF_8))
            }
        }
    }

    /**
     * Fallback when multicast is blocked: asks every host of each local /24 network directly.
     * [addresses] are this phone's IPv4 addresses. Returns when the scan is done.
     */
    fun scanSubnets(addresses: List<String>, targetPort: Int = LocalSend.PORT, timeoutMs: Int = 700) {
        val self = addresses.toSet()
        val hosts = addresses.flatMap { ip ->
            val p = ip.split('.')
            if (p.size != 4) emptyList() else (1..254).map { "${p[0]}.${p[1]}.${p[2]}.$it" }
        }.distinct().filter { it !in self }
        if (hosts.isEmpty()) return
        val pool = Executors.newFixedThreadPool(48) { r -> Thread(r, "ls-scan").apply { isDaemon = true } }
        for (h in hosts) {
            pool.execute {
                // Try HTTPS first (LocalSend default), then plain HTTP.
                val d = client.register(h, targetPort, https = true, timeoutMs = timeoutMs)
                    ?: client.register(h, targetPort, https = false, timeoutMs = timeoutMs)
                if (d != null && d.fingerprint != selfInfo().fingerprint) onPeer(d)
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
    }

    companion object {
        /** Up, multicast-capable, non-loopback interfaces with an IPv4 address (Wi-Fi, hotspot, Ethernet). */
        fun interfaces(): List<NetworkInterface> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { nif ->
                runCatching { nif.isUp && !nif.isLoopback && nif.supportsMulticast() }.getOrDefault(false) &&
                    nif.inetAddresses.toList().any { it is Inet4Address }
            }
        }.getOrDefault(emptyList())

        /** This phone's private IPv4 addresses (for the subnet scan and the Receive screen). */
        fun localIpv4(): List<String> = interfaces().flatMap { nif ->
            nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }
        }.distinct()
    }
}
