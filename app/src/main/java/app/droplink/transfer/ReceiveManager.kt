package app.droplink.transfer

import android.content.Context
import app.droplink.core.localsend.LsDecision
import app.droplink.core.localsend.LsFile
import app.droplink.core.localsend.LsIncomingSession
import app.droplink.core.localsend.LsOutcome
import app.droplink.core.localsend.LsServerCallbacks
import app.droplink.core.protocol.FileEntry
import app.droplink.core.protocol.SafePath
import app.droplink.data.AppDatabase
import app.droplink.data.Direction
import app.droplink.data.RecentDeviceEntity
import app.droplink.data.SettingsRepository
import app.droplink.data.TrustedDeviceEntity
import app.droplink.engine.LocalSendEngine
import app.droplink.engine.NearbyEngine
import app.droplink.engine.NearbyOffer
import app.droplink.engine.NearbyReceiveListener
import app.droplink.net.LinkProblem
import app.droplink.net.NetUtils
import app.droplink.service.Notifications
import app.droplink.service.TransferService
import app.droplink.util.DiagLog
import app.droplink.util.Perms
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class LinkState { OFF, STARTING, ON, FAILED }

data class ReceiveStatus(
    val active: Boolean = false,
    val nearby: LinkState = LinkState.OFF,
    val nearbyProblem: LinkProblem? = null,
    val localSend: LinkState = LinkState.OFF,
    val localSendProblem: LinkProblem? = null,
    /** This phone's address on the network, shown so people can check both phones are on the same Wi-Fi. */
    val ip: String? = null,
)

data class PendingRequest(
    val key: String,
    val senderName: String,
    /** Identifier used for "always accept" (LocalSend fingerprint / Nearby device id). */
    val trustKey: String?,
    val fileCount: Int,
    val totalBytes: Long,
    /** Nearby's 4-character code; both phones show it. Null over LocalSend. */
    val pairingCode: String?,
    val sampleNames: List<String>,
    val alreadyTrusted: Boolean,
    val mode: ConnectionMode,
)

/** Receive mode: Nearby advertising + the LocalSend server, one transfer at a time. */
class ReceiveManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val db: () -> AppDatabase,
    private val tracker: TransferTracker,
    private val history: HistoryRecorder,
    private val nearby: NearbyEngine,
    private val localSend: LocalSendEngine,
) {
    private val _status = MutableStateFlow(ReceiveStatus())
    val status: StateFlow<ReceiveStatus> = _status.asStateFlow()

    private val _pending = MutableStateFlow<PendingRequest?>(null)
    val pending: StateFlow<PendingRequest?> = _pending.asStateFlow()

    private val decisions = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, Boolean>>>()
    private var startJob: Job? = null
    /** Which engine carries the transfer on screen. */
    @Volatile private var activeMode: ConnectionMode? = null
    /** LocalSend file id → index in the tracker. */
    private val lsIndex = ConcurrentHashMap<String, Int>()
    @Volatile private var lsSession: String? = null

    @Synchronized
    fun start() {
        if (_status.value.active) return
        _status.value = ReceiveStatus(active = true)
        TransferService.start(context)
        localSend.incoming = lsCallbacks
        val mode = settings.current.forcedMode
        startJob = scope.launch(Dispatchers.IO) {
            // LocalSend: works on every ROM, needs the same Wi-Fi / hotspot.
            if (mode != ConnectionMode.NEARBY) {
                _status.update { it.copy(localSend = LinkState.STARTING) }
                localSend.acquire(LocalSendEngine.USER_RECEIVE)
                val ip = NetUtils.privateIpv4ByInterface().entries.sortedBy { if (it.key.startsWith("wlan")) 0 else 1 }.firstOrNull()?.value
                _status.update {
                    when {
                        localSend.port.value <= 0 -> it.copy(localSend = LinkState.FAILED, localSendProblem = LinkProblem.NO_NETWORK)
                        ip == null -> it.copy(localSend = LinkState.ON, localSendProblem = LinkProblem.NO_NETWORK, ip = null)
                        else -> it.copy(localSend = LinkState.ON, localSendProblem = null, ip = ip)
                    }
                }
                localSend.announce()
            }
            // Nearby: works without any shared network, needs Google Play services.
            if (mode != ConnectionMode.LOCALSEND) startNearby()
        }
    }

    private suspend fun startNearby() {
        val problem = when {
            !nearby.isAvailable -> LinkProblem.NEARBY_UNAVAILABLE
            !Perms.granted(context, Perms.nearby()) -> LinkProblem.PERMISSION_DENIED
            !Perms.locationServicesOn(context) -> LinkProblem.LOCATION_OFF
            else -> null
        }
        if (problem != null) {
            _status.update { it.copy(nearby = LinkState.FAILED, nearbyProblem = problem) }
            return
        }
        _status.update { it.copy(nearby = LinkState.STARTING, nearbyProblem = null) }
        val err = nearby.startAdvertising(settings.current.deviceName, nearbyListener)
        _status.update {
            if (!it.active) it
            else if (err == null) it.copy(nearby = LinkState.ON)
            else it.copy(nearby = LinkState.FAILED, nearbyProblem = SendManager.problemFor(err))
        }
    }

    /** Retry Nearby after the user granted a permission or switched Bluetooth on. */
    fun retryNearby() {
        if (!_status.value.active) return
        scope.launch(Dispatchers.IO) { startNearby() }
    }

    @Synchronized
    fun stop() {
        startJob?.cancel()
        nearby.stopAdvertising()
        localSend.cancelIncoming()
        localSend.incoming = null
        localSend.release(LocalSendEngine.USER_RECEIVE)
        decisions.values.forEach { it.complete(false to false) }
        decisions.clear()
        _pending.value = null
        Notifications.cancelIncoming(context)
        _status.value = ReceiveStatus()
    }

    /** Answer from the accept/decline dialog or notification. */
    fun respond(key: String, accept: Boolean, trust: Boolean) {
        decisions[key]?.complete(accept to trust)
    }

    fun cancelTransfer() {
        when (activeMode) {
            ConnectionMode.NEARBY -> nearby.cancelIncoming()
            ConnectionMode.LOCALSEND -> localSend.cancelIncoming()
            null -> Unit
        }
    }

    /** Asks the user (dialog + notification). Suspends up to [APPROVAL_TIMEOUT_MS]. Returns (accept, trust). */
    private suspend fun ask(request: PendingRequest): Pair<Boolean, Boolean> {
        if (tracker.current?.phase?.isActive == true) return false to false // busy
        if (request.alreadyTrusted && settings.current.autoAcceptTrusted) return true to false
        val deferred = CompletableDeferred<Pair<Boolean, Boolean>>()
        decisions[request.key] = deferred
        _pending.value = request
        Notifications.showIncoming(context, request)
        val answer = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } ?: (false to false)
        decisions.remove(request.key)
        if (_pending.value?.key == request.key) _pending.value = null
        Notifications.cancelIncoming(context)
        if (answer.first && answer.second && request.trustKey != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { db().trusted().upsert(TrustedDeviceEntity(request.trustKey, request.senderName, request.trustKey, System.currentTimeMillis())) }
            }
        }
        return answer
    }

    private suspend fun isTrusted(key: String?): Boolean =
        key != null && runCatching { db().trusted().get(key) }.getOrNull() != null

    private fun begin(id: String, mode: ConnectionMode, sender: String, trustKey: String?, files: List<FileEntry>, code: String?) {
        activeMode = mode
        tracker.begin(id, Role.RECEIVE, sender, trustKey, files, mode)
        tracker.startData(emptyList(), emptyList(), code)
        TransferService.start(context)
        scope.launch(Dispatchers.IO) {
            runCatching { db().recent().upsert(RecentDeviceEntity(sender.lowercase(), sender, mode.name, System.currentTimeMillis())) }
        }
    }

    // --------------------------------------------------------------------------------- Nearby

    private val nearbyListener = object : NearbyReceiveListener {
        override fun onOffer(offer: NearbyOffer, respond: (Boolean) -> Unit) {
            scope.launch {
                val trustKey = offer.deviceId
                val request = PendingRequest(
                    key = "nearby:" + offer.endpointId,
                    senderName = offer.senderName,
                    trustKey = trustKey,
                    fileCount = offer.files.size,
                    totalBytes = offer.files.sumOf { it.size },
                    pairingCode = offer.authDigits,
                    sampleNames = offer.files.take(3).map { it.name },
                    alreadyTrusted = isTrusted(trustKey),
                    mode = ConnectionMode.NEARBY,
                )
                respond(ask(request).first)
            }
        }

        override fun onOfferWithdrawn(endpointId: String) {
            decisions["nearby:$endpointId"]?.complete(false to false)
        }

        override fun onStarted(offer: NearbyOffer) {
            DiagLog.log("Receive: Nearby transfer of ${offer.files.size} file(s) from ${offer.senderName}")
            begin("nearby-" + UUID.randomUUID(), ConnectionMode.NEARBY, offer.senderName, offer.deviceId, offer.files, offer.authDigits)
        }

        override fun onProgress(index: Int, bytes: Long) {
            if (activeMode != ConnectionMode.NEARBY) return
            if (tracker.current?.files?.getOrNull(index)?.status == FileStatus.WAITING) tracker.fileStarted(index, 0)
            tracker.bytes(index, bytes)
        }

        override fun onFileDone(index: Int, ok: Boolean, reason: String?, location: String?) {
            if (activeMode == ConnectionMode.NEARBY) tracker.fileFinished(index, ok, reason, location)
        }

        override fun onEnded(completed: Boolean, cancelledByPeer: Boolean, reason: String?) {
            if (activeMode != ConnectionMode.NEARBY) return
            when {
                completed -> finish(Phase.COMPLETED, null)
                cancelledByPeer -> finish(Phase.CANCELLED, TransferError.PeerCancelled)
                reason == null -> finish(Phase.CANCELLED, null)
                else -> finish(Phase.FAILED, TransferError.ConnectionLost, reason)
            }
        }
    }

    // ------------------------------------------------------------------------------ LocalSend

    private val lsCallbacks = object : LsServerCallbacks {
        override fun onPrepareUpload(session: LsIncomingSession): LsDecision {
            if (!_status.value.active) return LsDecision.Decline("Not receiving")
            if (tracker.current?.phase?.isActive == true) return LsDecision.Busy
            val trustKey = session.sender.fingerprint.takeIf { it.isNotBlank() }
            val request = PendingRequest(
                key = "ls:" + session.sessionId,
                senderName = session.sender.alias,
                trustKey = trustKey,
                fileCount = session.files.size,
                totalBytes = session.files.sumOf { it.size },
                pairingCode = null,
                sampleNames = session.files.take(3).map { it.fileName.substringAfterLast('/') },
                alreadyTrusted = runBlocking { isTrusted(trustKey) },
                mode = ConnectionMode.LOCALSEND,
            )
            val (yes, _) = runBlocking { ask(request) }
            return if (yes) LsDecision.Accept(session.files.map { it.id }.toSet()) else LsDecision.Decline("Declined")
        }

        override fun onSessionStarted(session: LsIncomingSession, accepted: List<LsFile>) {
            DiagLog.log("Receive: LocalSend transfer of ${accepted.size} file(s) from ${session.sender.alias} (${session.sender.ip})")
            lsIndex.clear()
            accepted.forEachIndexed { i, f -> lsIndex[f.id] = i }
            lsSession = session.sessionId
            begin(
                "ls-" + session.sessionId, ConnectionMode.LOCALSEND, session.sender.alias, session.sender.fingerprint,
                accepted.map { FileEntry(SafePath.sanitizeOrDefault(it.fileName), it.size, it.fileType) }, null,
            )
        }

        override fun onFileProgress(sessionId: String, fileId: String, bytes: Long) {
            if (sessionId != lsSession) return
            val i = lsIndex[fileId] ?: return
            if (tracker.current?.files?.getOrNull(i)?.status == FileStatus.WAITING) tracker.fileStarted(i, 0)
            tracker.bytes(i, bytes)
        }

        override fun onFileFinished(sessionId: String, fileId: String, ok: Boolean, reason: String?, location: String?) {
            if (sessionId != lsSession) return
            val i = lsIndex[fileId] ?: return
            tracker.fileFinished(i, ok, reason, location)
        }

        override fun onSessionEnded(sessionId: String, outcome: LsOutcome) {
            if (sessionId != lsSession) return
            lsSession = null
            when (outcome) {
                LsOutcome.Completed -> finish(Phase.COMPLETED, null)
                LsOutcome.CancelledBySender -> finish(Phase.CANCELLED, TransferError.PeerCancelled)
                LsOutcome.CancelledHere -> finish(Phase.CANCELLED, null)
                is LsOutcome.Failed -> finish(Phase.FAILED, TransferError.ConnectionLost, outcome.reason)
            }
        }
    }

    private fun finish(phase: Phase, error: TransferError?, detail: String? = null) {
        DiagLog.log("Receive finished: $phase ${error ?: ""} ${detail ?: ""}")
        activeMode = null
        tracker.finish(phase, error, detail)
        tracker.current?.let { ui ->
            history.record(ui, Direction.RECEIVED)
            Notifications.showFinished(context, ui)
        }
    }

    private companion object {
        const val APPROVAL_TIMEOUT_MS = 110_000L
    }
}
