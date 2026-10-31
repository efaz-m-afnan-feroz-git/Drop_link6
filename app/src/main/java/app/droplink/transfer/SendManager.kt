package app.droplink.transfer

import android.content.Context
import app.droplink.core.localsend.LsDevice
import app.droplink.core.localsend.LsFile
import app.droplink.core.localsend.PrepareResult
import app.droplink.core.localsend.UploadResult
import app.droplink.core.transfer.CancelToken
import app.droplink.data.AppDatabase
import app.droplink.data.Direction
import app.droplink.data.RecentDeviceEntity
import app.droplink.data.SettingsRepository
import app.droplink.engine.LocalSendEngine
import app.droplink.engine.NearbyEngine
import app.droplink.engine.NearbyOutgoing
import app.droplink.engine.NearbySendListener
import app.droplink.engine.NearbySendResult
import app.droplink.net.LinkProblem
import app.droplink.service.Notifications
import app.droplink.service.TransferService
import app.droplink.storage.OutgoingItem
import app.droplink.storage.SourceResolver
import app.droplink.storage.openDescriptor
import app.droplink.util.DiagLog
import app.droplink.util.Perms
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/** A receiver found nearby, reachable over Nearby Connections, LocalSend, or both. */
data class Peer(
    val key: String,
    val name: String,
    /** Nearby Connections endpoint id (null if not found over Nearby). */
    val nearbyId: String?,
    /** LocalSend device (null if not found on the network). */
    val ls: LsDevice?,
) {
    val modes: List<ConnectionMode> get() = listOfNotNull(ConnectionMode.NEARBY.takeIf { nearbyId != null }, ConnectionMode.LOCALSEND.takeIf { ls != null })
}

/** Send side: selection, discovery over both methods, sending with automatic fallback. */
class SendManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val db: () -> AppDatabase,
    private val tracker: TransferTracker,
    private val history: HistoryRecorder,
    private val nearby: NearbyEngine,
    private val localSend: LocalSendEngine,
) {
    private val _selection = MutableStateFlow<List<OutgoingItem>>(emptyList())
    val selection: StateFlow<List<OutgoingItem>> = _selection.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    /** Why Nearby discovery isn't running (null when it is, or when it isn't wanted). */
    private val _nearbyProblem = MutableStateFlow<LinkProblem?>(null)
    val nearbyProblem: StateFlow<LinkProblem?> = _nearbyProblem.asStateFlow()

    private var discoveryJob: Job? = null
    private var sendJob: Job? = null
    @Volatile private var token: CancelToken? = null

    // ------------------------------------------------------------------------------ selection

    fun addItems(items: List<OutgoingItem>) = _selection.update { (it + items).distinctBy { i -> i.key } }
    fun removeItem(key: String) = _selection.update { list -> list.filterNot { it.key == key } }
    fun clearSelection() = _selection.update { emptyList() }

    // ------------------------------------------------------------------------------ discovery

    private val useNearby get() = settings.current.forcedMode != ConnectionMode.LOCALSEND
    private val useLocalSend get() = settings.current.forcedMode != ConnectionMode.NEARBY

    /** Looks for receivers with both methods while the Send screen is open (safe to call again). */
    fun startDiscovery() {
        _discovering.value = true
        if (useLocalSend) localSend.startSearching()
        if (useNearby) scope.launch { startNearbyDiscovery() } else _nearbyProblem.value = null
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            try {
                combine(nearby.endpoints, localSend.peers) { n, l -> merge(if (useNearby) n else emptyMap(), if (useLocalSend) l else emptyList()) }
                    .collect { _peers.value = it }
            } finally {
                _discovering.value = false
            }
        }
    }

    private suspend fun startNearbyDiscovery() {
        _nearbyProblem.value = when {
            !nearby.isAvailable -> LinkProblem.NEARBY_UNAVAILABLE
            !Perms.granted(context, Perms.nearby()) -> LinkProblem.PERMISSION_DENIED
            !Perms.locationServicesOn(context) -> LinkProblem.LOCATION_OFF
            else -> nearby.startDiscovery()?.let { problemFor(it) }
        }
        if (_nearbyProblem.value != null) DiagLog.log("Send: Nearby discovery not running: ${_nearbyProblem.value}")
    }

    /** Retry Nearby after the user fixed a permission / switched Bluetooth on. */
    fun retryNearby() {
        scope.launch { if (useNearby) startNearbyDiscovery() }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        nearby.stopDiscovery()
        localSend.stopSearching()
        _peers.value = emptyList()
    }

    private fun merge(nearbyPeers: Map<String, String>, lsPeers: List<LsDevice>): List<Peer> {
        val out = LinkedHashMap<String, Peer>()
        nearbyPeers.forEach { (id, name) ->
            val key = name.trim().lowercase()
            out[key] = Peer(key, name, id, out[key]?.ls)
        }
        lsPeers.forEach { d ->
            val key = d.alias.trim().lowercase()
            val existing = out[key]
            out[key] = existing?.copy(ls = d) ?: Peer(key, d.alias, null, d)
        }
        return out.values.sortedBy { it.name.lowercase() }
    }

    // ------------------------------------------------------------------------------ sending

    val isBusy: Boolean get() = tracker.current?.phase?.isActive == true

    /** Sends the current selection to [peer]: Nearby first (if both phones have it), LocalSend otherwise or as fallback. */
    fun send(peer: Peer) {
        if (isBusy) return
        val items = SourceResolver.uniquify(_selection.value)
        if (items.isEmpty()) return
        val id = UUID.randomUUID().toString()
        val mode = peer.modes.firstOrNull() ?: return
        tracker.begin(id, Role.SEND, peer.name, peer.ls?.fingerprint, items.map { it.entry }, mode)
        TransferService.start(context)
        val tok = CancelToken().also { token = it }
        sendJob = scope.launch(Dispatchers.IO + crashGuard(items)) {
            // Scanning competes with the data link for radio time.
            nearby.stopDiscovery()
            localSend.stopSearching()
            DiagLog.log("Send: ${items.size} file(s) to ${peer.name} via ${peer.modes}")
            var nearbyError: String? = null
            if (peer.nearbyId != null) {
                tracker.update { it.copy(mode = ConnectionMode.NEARBY) }
                when (val r = sendNearby(peer.nearbyId, items, tok)) {
                    is NearbySendResult.NotConnected -> {
                        DiagLog.log("Send: Nearby couldn't connect (${r.reason})")
                        nearbyError = r.reason
                        if (tok.isCancelled) return@launch finish(items, Phase.CANCELLED)
                    }
                    else -> return@launch onNearbyResult(items, r)
                }
            }
            val ls = peer.ls
            if (ls == null) {
                finish(items, Phase.FAILED, TransferError.CouldNotConnect, nearbyError)
                return@launch
            }
            tracker.update { it.copy(mode = ConnectionMode.LOCALSEND, phase = Phase.CONNECTING) }
            sendLocalSend(ls, items, tok)
        }
    }

    private suspend fun sendNearby(endpointId: String, items: List<OutgoingItem>, tok: CancelToken): NearbySendResult {
        val outgoing = items.map { item -> NearbyOutgoing(item.entry) { item.openDescriptor(context) } }
        val listener = object : NearbySendListener {
            override fun onConnected(authDigits: String?) {
                tracker.update { it.copy(phase = Phase.WAITING_APPROVAL, pairingCode = authDigits) }
            }
            override fun onAccepted() {
                tracker.startData(emptyList(), emptyList(), null)
            }
            override fun onProgress(index: Int, bytes: Long) {
                if (tracker.current?.files?.getOrNull(index)?.status == FileStatus.WAITING) tracker.fileStarted(index, 0)
                tracker.bytes(index, bytes)
            }
            override fun onFileDone(index: Int, ok: Boolean, reason: String?) = tracker.fileFinished(index, ok, reason)
        }
        return nearby.send(endpointId, settings.current.deviceName, settings.deviceId, outgoing, listener, tok)
    }

    private fun onNearbyResult(items: List<OutgoingItem>, r: NearbySendResult) {
        when (r) {
            is NearbySendResult.Completed -> finish(items, Phase.COMPLETED)
            is NearbySendResult.Declined -> {
                tracker.update { it.copy(declineReason = r.reason) }
                finish(items, Phase.DECLINED)
            }
            NearbySendResult.CancelledHere -> finish(items, Phase.CANCELLED)
            NearbySendResult.CancelledByPeer -> finish(items, Phase.CANCELLED, TransferError.PeerCancelled)
            is NearbySendResult.Failed -> finish(items, Phase.FAILED, TransferError.ConnectionLost, r.reason)
            is NearbySendResult.NotConnected -> finish(items, Phase.FAILED, TransferError.CouldNotConnect, r.reason)
        }
    }

    private suspend fun sendLocalSend(target: LsDevice, items: List<OutgoingItem>, tok: CancelToken) {
        val client = localSend.client
        val files = items.mapIndexed { i, item -> LsFile("f$i", item.entry.path, item.entry.size, item.entry.mime) }
        tracker.update { it.copy(phase = Phase.WAITING_APPROVAL) }
        val accepted = when (val r = client.prepareUpload(target, files, tok)) {
            is PrepareResult.Accepted -> r.response
            PrepareResult.NothingNeeded -> return finish(items, Phase.COMPLETED)
            PrepareResult.Declined -> return finish(items, Phase.DECLINED)
            PrepareResult.Busy -> return finish(items, Phase.FAILED, TransferError.Busy)
            PrepareResult.PinRequired -> return finish(items, Phase.FAILED, TransferError.PinRequired)
            is PrepareResult.Failed -> return if (tok.isCancelled) finish(items, Phase.CANCELLED)
            else finish(items, Phase.FAILED, TransferError.CouldNotConnect, "LocalSend ${r.code}: ${r.message}")
        }
        tracker.startData(emptyList(), emptyList(), null)
        // Files the receiver didn't ask for (LocalSend lets it pick) are marked as skipped.
        files.forEachIndexed { i, f -> if (accepted.tokens[f.id] == null) tracker.fileFinished(i, false, "Not accepted by the receiver") }

        // Two uploads at a time keeps the link busy between small files without thrashing big ones.
        val gate = Semaphore(2)
        val results = kotlinx.coroutines.coroutineScope {
            files.mapIndexed { i, f ->
                async(Dispatchers.IO) {
                    val tokenFor = accepted.tokens[f.id] ?: return@async true
                    gate.withPermit {
                        if (tok.isCancelled) return@withPermit false
                        tracker.fileStarted(i, 0)
                        val r = client.upload(target, accepted.sessionId, f, tokenFor, items[i].file, tok) { sent -> tracker.bytes(i, sent) }
                        when (r) {
                            UploadResult.Ok -> tracker.fileFinished(i, true, null)
                            UploadResult.ChecksumMismatch -> tracker.fileFinished(i, false, "Checksum mismatch")
                            UploadResult.Cancelled -> tracker.fileFinished(i, false, "Cancelled")
                            is UploadResult.Failed -> {
                                DiagLog.log("Send: LocalSend upload of ${f.fileName} failed: ${r.message}")
                                tracker.fileFinished(i, false, r.message)
                            }
                        }
                        r == UploadResult.Ok
                    }
                }
            }.awaitAll()
        }
        when {
            tok.isCancelled -> {
                runCatching { client.cancel(target, accepted.sessionId) }
                finish(items, Phase.CANCELLED)
            }
            results.none { it } && results.isNotEmpty() -> finish(items, Phase.FAILED, TransferError.ConnectionLost, tracker.current?.files?.firstNotNullOfOrNull { it.reason })
            else -> finish(items, Phase.COMPLETED)
        }
    }

    fun cancel() {
        token?.cancel("Cancelled on the sending phone")
    }

    /** Last-resort handler: an unexpected crash in a send job becomes a visible, logged failure. */
    private fun crashGuard(items: List<OutgoingItem>) = CoroutineExceptionHandler { _, e ->
        DiagLog.log("Send: crashed", e)
        runCatching { finish(items, Phase.FAILED, TransferError.Other(e.javaClass.simpleName), e.message) }
    }

    private fun finish(items: List<OutgoingItem>, phase: Phase, error: TransferError? = null, detail: String? = null) {
        if (phase == Phase.FAILED) DiagLog.log("Send failed: $error ${detail ?: ""}") else DiagLog.log("Send finished: $phase")
        tracker.finish(phase, error, detail)
        token = null
        val ui = tracker.current ?: return
        if (phase != Phase.DECLINED || ui.okCount > 0) {
            history.record(ui, Direction.SENT, items.associate { it.entry.path to it.key })
        }
        val mode = ui.mode
        if (mode != null && (phase == Phase.COMPLETED || ui.okCount > 0)) {
            scope.launch(Dispatchers.IO) {
                runCatching { db().recent().upsert(RecentDeviceEntity(ui.peerName.lowercase(), ui.peerName, mode.name, System.currentTimeMillis())) }
            }
        }
        Notifications.showFinished(context, ui)
        if (ui.phase == Phase.COMPLETED) clearSelection()
    }

    companion object {
        fun problemFor(statusCode: Int): LinkProblem = when (statusCode) {
            ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> LinkProblem.BLUETOOTH_OFF
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE,
            ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE -> LinkProblem.PERMISSION_DENIED
            ConnectionsStatusCodes.MISSING_SETTING_LOCATION_MUST_BE_ON -> LinkProblem.LOCATION_OFF
            else -> LinkProblem.NEARBY_FAILED
        }
    }
}
