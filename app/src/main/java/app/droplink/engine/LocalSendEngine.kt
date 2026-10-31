package app.droplink.engine

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import app.droplink.core.localsend.LocalSend
import app.droplink.core.localsend.LsClient
import app.droplink.core.localsend.LsDevice
import app.droplink.core.localsend.LsDiscovery
import app.droplink.core.localsend.LsDecision
import app.droplink.core.localsend.LsFile
import app.droplink.core.localsend.LsIncomingSession
import app.droplink.core.localsend.LsOutcome
import app.droplink.core.localsend.LsServer
import app.droplink.core.localsend.LsServerCallbacks
import app.droplink.core.security.IdentityStore
import app.droplink.core.transfer.SinkFactory
import app.droplink.data.SettingsRepository
import app.droplink.util.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * LocalSend open protocol on Android: works on every ROM (no Google services) and with the
 * official LocalSend app on phones and PCs. Needs both devices on the same Wi-Fi network or one
 * device's hotspot.
 *
 * The server runs whenever DropLink is open (like LocalSend itself), because discovery answers
 * arrive as HTTP calls to it.
 */
class LocalSendEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val identity: IdentityStore,
    private val settings: SettingsRepository,
    private val sinks: () -> SinkFactory,
) {
    private val appContext = context.applicationContext

    /** Set by the receive side: decides about and tracks incoming transfers. */
    @Volatile var incoming: LsServerCallbacks? = null

    private val _peers = MutableStateFlow<List<LsDevice>>(emptyList())
    val peers: StateFlow<List<LsDevice>> = _peers.asStateFlow()
    private val seen = ConcurrentHashMap<String, Pair<LsDevice, Long>>()

    private val _port = MutableStateFlow(-1)
    /** Port of this phone's LocalSend server (-1 while stopped). */
    val port: StateFlow<Int> = _port.asStateFlow()

    private var server: LsServer? = null
    private var discovery: LsDiscovery? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var refreshJob: Job? = null

    val client: LsClient by lazy { LsClient(identity.get()) { selfInfo() } }

    fun selfInfo(): LsDevice = LsDevice(
        alias = settings.current.deviceName,
        deviceModel = Build.MODEL,
        deviceType = "mobile",
        fingerprint = identity.get().fingerprint,
        port = server?.port?.takeIf { it > 0 } ?: LocalSend.PORT,
        protocol = "https",
    )

    private val serverCallbacks = object : LsServerCallbacks {
        override fun onRegister(device: LsDevice) = addPeer(device)
        override fun onPrepareUpload(session: LsIncomingSession): LsDecision =
            incoming?.onPrepareUpload(session) ?: LsDecision.Decline("Not receiving")
        override fun onSessionStarted(session: LsIncomingSession, accepted: List<LsFile>) {
            incoming?.onSessionStarted(session, accepted)
        }
        override fun onFileProgress(sessionId: String, fileId: String, bytes: Long) {
            incoming?.onFileProgress(sessionId, fileId, bytes)
        }
        override fun onFileFinished(sessionId: String, fileId: String, ok: Boolean, reason: String?, location: String?) {
            incoming?.onFileFinished(sessionId, fileId, ok, reason, location)
        }
        override fun onSessionEnded(sessionId: String, outcome: LsOutcome) {
            incoming?.onSessionEnded(sessionId, outcome)
        }
    }

    /** Starts the server and multicast discovery (idempotent, call from a background thread). */
    @Synchronized
    fun start() {
        if (server?.isRunning == true) return
        try {
            val s = LsServer(identity.get(), { selfInfo() }, sinks, serverCallbacks)
            _port.value = s.start()
            server = s
        } catch (e: Exception) {
            DiagLog.log("LocalSend: server failed to start", e)
            return
        }
        multicastLock = appContext.getSystemService(WifiManager::class.java)?.createMulticastLock("droplink-localsend")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        try {
            discovery = LsDiscovery({ selfInfo() }, client, ::addPeer).also { it.start() }
        } catch (e: Exception) {
            DiagLog.log("LocalSend: multicast discovery unavailable (port ${LocalSend.PORT} busy?)", e)
        }
    }

    @Synchronized
    fun stop() {
        discovery?.stop()
        discovery = null
        server?.stop()
        server = null
        _port.value = -1
        runCatching { multicastLock?.release() }
        multicastLock = null
        seen.clear()
        publish()
    }

    private val users = HashSet<String>()

    /** Keeps the server running while [user] needs it (receive mode, or a Send screen searching). Blocking. */
    fun acquire(user: String) {
        synchronized(users) { users += user }
        start()
    }

    /** Stops the server once nobody needs it any more. */
    fun release(user: String) {
        val idle = synchronized(users) {
            users -= user
            users.isEmpty()
        }
        if (idle) scope.launch(Dispatchers.IO) { synchronized(users) { if (users.isEmpty()) stop() } }
    }

    /** While a Send screen is open: announce every few seconds and scan the subnet once if multicast finds nobody. */
    fun startSearching() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch(Dispatchers.IO) {
            acquire(USER_SEARCH)
            var round = 0
            while (isActive) {
                runCatching { discovery?.announce() }
                expireOld()
                if (round == 2 && _peers.value.isEmpty()) {
                    DiagLog.log("LocalSend: no multicast answers, scanning ${LsDiscovery.localIpv4()}")
                    runCatching { discovery?.scanSubnets(LsDiscovery.localIpv4()) }
                }
                round++
                delay(if (round < 4) 2_000 else 8_000)
            }
        }
    }

    fun stopSearching() {
        val job = refreshJob ?: return
        job.cancel()
        refreshJob = null
        release(USER_SEARCH)
    }

    /** Announce once (e.g. when receive mode starts), so senders see this phone immediately. */
    fun announce() {
        scope.launch(Dispatchers.IO) {
            repeat(3) {
                runCatching { discovery?.announce() }
                delay(1_000)
            }
        }
    }

    fun cancelIncoming() = server?.cancelSession()

    private fun addPeer(device: LsDevice) {
        if (device.fingerprint == runCatching { identity.get().fingerprint }.getOrNull()) return
        val key = device.fingerprint.ifBlank { "${device.ip}:${device.port}" }
        val prev = seen[key]?.first
        // Keep the address we learned (register calls carry the caller's IP).
        val merged = if (device.ip == null && prev != null) device.copy(ip = prev.ip) else device
        seen[key] = merged to System.currentTimeMillis()
        if (prev == null) DiagLog.log("LocalSend: found ${merged.alias} at ${merged.ip}:${merged.port} (${merged.protocol})")
        publish()
    }

    private fun expireOld() {
        val cutoff = System.currentTimeMillis() - 60_000
        if (seen.entries.removeIf { it.value.second < cutoff }) publish()
    }

    private fun publish() {
        _peers.update { seen.values.map { it.first }.filter { it.ip != null }.sortedBy { it.alias.lowercase() } }
    }

    companion object {
        const val USER_RECEIVE = "receive"
        const val USER_SEARCH = "search"
    }
}
