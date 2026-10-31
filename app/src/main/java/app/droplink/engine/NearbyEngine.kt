package app.droplink.engine

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import app.droplink.core.protocol.FileEntry
import app.droplink.core.protocol.Json
import app.droplink.core.protocol.SafePath
import app.droplink.core.transfer.CancelToken
import app.droplink.core.transfer.FileSink
import app.droplink.core.transfer.SinkFactory
import app.droplink.util.DiagLog
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** A file the sender offers over Nearby; [open] is called only when the file is about to be sent. */
class NearbyOutgoing(val entry: FileEntry, val open: () -> ParcelFileDescriptor)

data class NearbyOffer(
    val endpointId: String,
    val senderName: String,
    val deviceId: String?,
    val authDigits: String?,
    val files: List<FileEntry>,
)

sealed class NearbySendResult {
    data class Completed(val ok: List<Boolean>, val reasons: List<String?>) : NearbySendResult()
    data class Declined(val reason: String?) : NearbySendResult()
    data object CancelledHere : NearbySendResult()
    data object CancelledByPeer : NearbySendResult()
    /** Couldn't connect at all (the caller may fall back to LocalSend). */
    data class NotConnected(val reason: String) : NearbySendResult()
    data class Failed(val reason: String) : NearbySendResult()
}

interface NearbySendListener {
    fun onConnected(authDigits: String?) {}
    fun onAccepted() {}
    fun onProgress(index: Int, bytes: Long) {}
    fun onFileDone(index: Int, ok: Boolean, reason: String?) {}
}

interface NearbyReceiveListener {
    /** Show the accept dialog; call [respond] with the user's answer (from any thread). */
    fun onOffer(offer: NearbyOffer, respond: (Boolean) -> Unit)
    /** The sender gave up / cancelled before the user answered: close the dialog. */
    fun onOfferWithdrawn(endpointId: String) {}
    fun onStarted(offer: NearbyOffer) {}
    fun onProgress(index: Int, bytes: Long) {}
    fun onFileDone(index: Int, ok: Boolean, reason: String?, location: String?) {}
    fun onEnded(completed: Boolean, cancelledByPeer: Boolean, reason: String?) {}
}

/**
 * Google Nearby Connections (the engine behind Quick Share). Discovery runs over Bluetooth/BLE
 * and Wi-Fi; Nearby then upgrades the link to the fastest medium both phones support
 * (Wi-Fi hotspot / Wi-Fi Direct / LAN) on its own. Needs Google Play services.
 *
 * Messages (JSON, BYTES payloads):
 *  sender → receiver: offer {part, parts, name, dev, files:[{i,n,s,m}]}, file {i, p}, cancel
 *  receiver → sender: accept | decline {reason}, saved {i, ok, r}, cancel
 * Files travel as FILE payloads; "file" tells the receiver which payload id carries file i.
 */
class NearbyEngine(context: Context, private val sinks: () -> SinkFactory) {
    private val appContext = context.applicationContext

    val isAvailable: Boolean
        get() = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
        }.getOrDefault(false)

    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(appContext) }
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "nearby-io").apply { isDaemon = true } }

    // ------------------------------------------------------------------------------- discovery

    private val _endpoints = MutableStateFlow<Map<String, String>>(emptyMap())
    /** endpointId → device name of DropLink receivers nearby. */
    val endpoints: StateFlow<Map<String, String>> = _endpoints.asStateFlow()
    @Volatile private var discovering = false

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            DiagLog.log("Nearby: found \"${info.endpointName}\" ($endpointId)")
            _endpoints.update { it + (endpointId to info.endpointName) }
        }

        override fun onEndpointLost(endpointId: String) {
            _endpoints.update { it - endpointId }
        }
    }

    /** Returns null on success, or a status code explaining why discovery couldn't start. */
    suspend fun startDiscovery(): Int? {
        if (discovering) return null
        val err = client.startDiscovery(SERVICE_ID, discoveryCallback, DiscoveryOptions.Builder().setStrategy(STRATEGY).build()).awaitError()
        if (err == null || err == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
            discovering = true
            DiagLog.log("Nearby: discovering")
            return null
        }
        DiagLog.log("Nearby: discovery failed: ${describe(err)}")
        return err
    }

    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        runCatching { client.stopDiscovery() }
        _endpoints.value = emptyMap()
    }

    // ---------------------------------------------------------------------------------- sending

    private sealed class Ev {
        data class Initiated(val info: ConnectionInfo) : Ev()
        data class Result(val code: Int) : Ev()
        data object Disconnected : Ev()
        data class Bytes(val msg: Map<String, Any?>) : Ev()
        data class Update(val u: PayloadTransferUpdate) : Ev()
        data object Cancel : Ev()
    }

    /** Sends [files] to [endpointId]. Suspends until done; never throws. */
    suspend fun send(
        endpointId: String,
        myName: String,
        deviceId: String,
        files: List<NearbyOutgoing>,
        listener: NearbySendListener,
        cancel: CancelToken,
    ): NearbySendResult {
        val events = Channel<Ev>(Channel.UNLIMITED)
        val payloadCb = object : PayloadCallback() {
            override fun onPayloadReceived(id: String, payload: Payload) {
                if (payload.type == Payload.Type.BYTES) parse(payload.asBytes())?.let { events.trySend(Ev.Bytes(it)) }
            }

            override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
                events.trySend(Ev.Update(update))
            }
        }
        val lifecycle = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(id: String, info: ConnectionInfo) { events.trySend(Ev.Initiated(info)) }
            override fun onConnectionResult(id: String, res: ConnectionResolution) { events.trySend(Ev.Result(res.status.statusCode)) }
            override fun onDisconnected(id: String) { events.trySend(Ev.Disconnected) }
            override fun onBandwidthChanged(id: String, info: BandwidthInfo) {
                DiagLog.log("Nearby: link quality ${info.quality} (1 = Bluetooth, slow; 3 = Wi-Fi, fast)")
            }
        }
        cancel.onCancel { events.trySend(Ev.Cancel) }

        val open = arrayOfNulls<ParcelFileDescriptor>(files.size)
        val idToIndex = HashMap<Long, Int>()
        val payloadIds = LongArray(files.size)
        var connected = false
        try {
            // 1. Connect. Both sides accept; both screens show the same 4 characters.
            val reqErr = client.requestConnection(myName, endpointId, lifecycle).awaitError()
            when (reqErr) {
                null, ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> Unit
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> return NearbySendResult.Declined("Busy")
                else -> return NearbySendResult.NotConnected(describe(reqErr))
            }
            when (val init = next(events, 45_000) { it is Ev.Initiated || it is Ev.Result || it is Ev.Disconnected || it is Ev.Cancel }) {
                is Ev.Initiated -> {
                    listener.onConnected(init.info.authenticationDigits)
                    client.acceptConnection(endpointId, payloadCb).awaitError()?.let { return NearbySendResult.NotConnected(describe(it)) }
                }
                Ev.Cancel -> return NearbySendResult.CancelledHere
                null -> return NearbySendResult.NotConnected("No answer from the other phone")
                is Ev.Result -> return NearbySendResult.NotConnected(describe(init.code))
                else -> return NearbySendResult.NotConnected("Connection refused")
            }
            when (val r = next(events, 45_000) { it is Ev.Result || it is Ev.Disconnected || it is Ev.Cancel }) {
                is Ev.Result -> when (r.code) {
                    ConnectionsStatusCodes.STATUS_OK -> Unit
                    ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> return NearbySendResult.Declined("Busy")
                    else -> return NearbySendResult.NotConnected(describe(r.code))
                }
                Ev.Cancel -> return NearbySendResult.CancelledHere
                else -> return NearbySendResult.NotConnected("Connection failed")
            }
            connected = true
            DiagLog.log("Nearby: connected to $endpointId, offering ${files.size} file(s)")

            // 2. Offer the files, split so each message stays well under the 32 KB BYTES limit.
            val parts = ArrayList<List<Map<String, Any?>>>()
            var current = ArrayList<Map<String, Any?>>()
            var size = 0
            files.forEachIndexed { i, f ->
                val e = mapOf("i" to i.toLong(), "n" to f.entry.path, "s" to f.entry.size, "m" to f.entry.mime)
                val len = Json.stringify(e).length + 1
                if (current.isNotEmpty() && size + len > 20_000) {
                    parts += current
                    current = ArrayList()
                    size = 0
                }
                current += e
                size += len
            }
            parts += current
            parts.forEachIndexed { pi, part ->
                sendJson(endpointId, mapOf("t" to "offer", "part" to pi.toLong(), "parts" to parts.size.toLong(), "name" to myName, "dev" to deviceId, "files" to part))
            }

            // 3. Wait for the user on the other phone.
            when (val a = next(events, 150_000) { it is Ev.Bytes || it is Ev.Disconnected || it is Ev.Cancel }) {
                is Ev.Bytes -> when (a.msg["t"]) {
                    "accept" -> listener.onAccepted()
                    "cancel" -> return NearbySendResult.CancelledByPeer
                    else -> return NearbySendResult.Declined(a.msg["reason"] as? String)
                }
                Ev.Cancel -> return cancelAll(endpointId, idToIndex.keys)
                null -> return NearbySendResult.Failed("The other phone didn't answer")
                else -> return NearbySendResult.Failed("Connection lost")
            }

            // 4. Stream the files, a few in flight at once so the link stays busy between small files.
            val sent = BooleanArray(files.size)
            val ok = arrayOfNulls<Boolean>(files.size)
            val reasons = arrayOfNulls<String>(files.size)
            var nextToSend = 0
            var inFlight = 0
            var resolved = 0
            fun close(i: Int) {
                runCatching { open[i]?.close() }
                open[i] = null
            }
            fun resolve(i: Int, good: Boolean, reason: String?) {
                if (ok[i] != null) return
                ok[i] = good
                reasons[i] = reason
                resolved++
                listener.onFileDone(i, good, reason)
            }
            fun pump() {
                while (inFlight < WINDOW && nextToSend < files.size) {
                    val i = nextToSend++
                    val payload = try {
                        Payload.fromFile(files[i].open().also { open[i] = it })
                    } catch (e: Exception) {
                        DiagLog.log("Nearby: can't open ${files[i].entry.name}", e)
                        close(i)
                        sent[i] = true
                        sendJson(endpointId, mapOf("t" to "skip", "i" to i.toLong()))
                        resolve(i, false, e.message ?: "Can't read the file")
                        continue
                    }
                    payloadIds[i] = payload.id
                    idToIndex[payload.id] = i
                    sendJson(endpointId, mapOf("t" to "file", "i" to i.toLong(), "p" to payload.id))
                    client.sendPayload(endpointId, payload)
                    inFlight++
                }
            }
            pump()
            while (resolved < files.size) {
                // Waiting only for the receiver to finish saving big files can take a while.
                val waitingForSaves = (0 until nextToSend).any { sent[it] && ok[it] == null }
                val ev = next(events, if (waitingForSaves && inFlight == 0) SAVE_TIMEOUT else IDLE_TIMEOUT) { true }
                    ?: return NearbySendResult.Failed("The transfer stalled")
                when (ev) {
                    is Ev.Update -> {
                        val i = idToIndex[ev.u.payloadId] ?: continue
                        when (ev.u.status) {
                            PayloadTransferUpdate.Status.IN_PROGRESS -> listener.onProgress(i, ev.u.bytesTransferred)
                            PayloadTransferUpdate.Status.SUCCESS -> if (!sent[i]) {
                                sent[i] = true
                                inFlight--
                                close(i)
                                listener.onProgress(i, files[i].entry.size)
                                pump()
                            }
                            else -> if (!sent[i]) {
                                sent[i] = true
                                inFlight--
                                close(i)
                                resolve(i, false, "Transfer failed")
                                pump()
                            }
                        }
                    }
                    is Ev.Bytes -> when (ev.msg["t"]) {
                        "saved" -> {
                            val i = (ev.msg["i"] as? Number)?.toInt() ?: continue
                            if (i in files.indices) resolve(i, ev.msg["ok"] == true, ev.msg["r"] as? String)
                        }
                        "cancel" -> return NearbySendResult.CancelledByPeer
                    }
                    Ev.Cancel -> return cancelAll(endpointId, idToIndex.keys)
                    Ev.Disconnected -> return NearbySendResult.Failed("Connection lost")
                    else -> Unit
                }
            }
            DiagLog.log("Nearby: all ${files.size} file(s) resolved")
            return NearbySendResult.Completed(ok.map { it == true }, reasons.toList())
        } catch (e: Exception) {
            DiagLog.log("Nearby: send failed", e)
            return if (connected) NearbySendResult.Failed(e.message ?: "Transfer failed") else NearbySendResult.NotConnected(e.message ?: "Couldn't connect")
        } finally {
            events.close()
            runCatching { client.disconnectFromEndpoint(endpointId) }
            open.forEach { runCatching { it?.close() } }
        }
    }

    private fun cancelAll(endpointId: String, payloadIds: Collection<Long>): NearbySendResult {
        payloadIds.forEach { runCatching { client.cancelPayload(it) } }
        runCatching { sendJson(endpointId, mapOf("t" to "cancel")) }
        // Give the cancel message a moment to leave before the connection is dropped.
        runCatching { Thread.sleep(300) }
        return NearbySendResult.CancelledHere
    }

    private suspend fun next(events: Channel<Ev>, timeoutMs: Long, accept: (Ev) -> Boolean): Ev? =
        withTimeoutOrNull(timeoutMs) {
            var e: Ev
            do {
                e = events.receive()
            } while (!accept(e))
            e
        }

    // -------------------------------------------------------------------------------- receiving

    @Volatile private var advertising = false
    @Volatile private var receiveListener: NearbyReceiveListener? = null
    @Volatile private var incoming: Incoming? = null

    /** Returns null on success, or a status code explaining why advertising couldn't start. */
    suspend fun startAdvertising(name: String, listener: NearbyReceiveListener): Int? {
        receiveListener = listener
        if (advertising) return null
        val err = client.startAdvertising(name, SERVICE_ID, advertiseLifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build()).awaitError()
        if (err == null || err == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
            advertising = true
            DiagLog.log("Nearby: advertising as \"$name\"")
            return null
        }
        DiagLog.log("Nearby: advertising failed: ${describe(err)}")
        return err
    }

    fun stopAdvertising() {
        if (incoming != null) cancelIncoming()
        advertising = false
        runCatching { client.stopAdvertising() }
    }

    /** Cancels the incoming transfer from this phone. */
    fun cancelIncoming() {
        val s = incoming ?: return
        runCatching { sendJson(s.endpointId, mapOf("t" to "cancel")) }
        s.cancelPayloads()
        s.end(completed = false, cancelledByPeer = false, reason = null)
    }

    private val advertiseLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (incoming != null) {
                DiagLog.log("Nearby: rejecting ${info.endpointName}, already busy")
                runCatching { client.rejectConnection(endpointId) } // one transfer at a time
                return
            }
            DiagLog.log("Nearby: connection from \"${info.endpointName}\"")
            val s = Incoming(endpointId, info.endpointName, info.authenticationDigits)
            incoming = s
            client.acceptConnection(endpointId, s.callback)
        }

        override fun onConnectionResult(endpointId: String, res: ConnectionResolution) {
            val code = res.status.statusCode
            if (code != ConnectionsStatusCodes.STATUS_OK) {
                DiagLog.log("Nearby: incoming connection failed: ${describe(code)}")
                if (incoming?.endpointId == endpointId) incoming = null
            }
        }

        override fun onDisconnected(endpointId: String) {
            val s = incoming ?: return
            if (s.endpointId == endpointId) s.onDisconnected()
        }

        override fun onBandwidthChanged(endpointId: String, info: BandwidthInfo) {
            DiagLog.log("Nearby: incoming link quality ${info.quality} (1 = Bluetooth, slow; 3 = Wi-Fi, fast)")
        }
    }

    private inner class Incoming(val endpointId: String, val senderName: String, val digits: String?) {
        private val offerParts = HashMap<Int, List<Map<String, Any?>>>()
        private var expectedParts = -1
        private var deviceId: String? = null
        @Volatile private var files: List<FileEntry> = emptyList()
        private val indexByPayload = ConcurrentHashMap<Long, Int>()
        private val payloads = ConcurrentHashMap<Long, Payload>()
        /** FILE payloads that finished before we learned which file they carry. */
        private val finishedEarly = ConcurrentHashMap.newKeySet<Long>()
        private val results = ConcurrentHashMap<Int, Boolean>()
        @Volatile private var accepted = false
        @Volatile private var answered = false
        @Volatile private var ended = false
        private val sessionKey = "nearby-" + System.currentTimeMillis()

        val callback = object : PayloadCallback() {
            override fun onPayloadReceived(id: String, payload: Payload) {
                when (payload.type) {
                    Payload.Type.BYTES -> parse(payload.asBytes())?.let { onMessage(it) }
                    Payload.Type.FILE -> if (accepted && !ended) payloads[payload.id] = payload else runCatching { client.cancelPayload(payload.id) }
                    else -> runCatching { client.cancelPayload(payload.id) }
                }
            }

            override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {
                if (!payloads.containsKey(u.payloadId)) return // BYTES messages
                val i = indexByPayload[u.payloadId]
                when (u.status) {
                    PayloadTransferUpdate.Status.IN_PROGRESS -> if (i != null) receiveListener?.onProgress(i, u.bytesTransferred)
                    PayloadTransferUpdate.Status.SUCCESS -> if (i != null) saveLater(i, u.payloadId) else finishedEarly += u.payloadId
                    else -> {
                        payloads.remove(u.payloadId)
                        if (i != null) finishFile(i, false, "Transfer failed", null)
                    }
                }
            }
        }

        fun cancelPayloads() {
            payloads.keys.forEach { runCatching { client.cancelPayload(it) } }
        }

        @Suppress("UNCHECKED_CAST")
        private fun onMessage(m: Map<String, Any?>) {
            when (m["t"]) {
                "offer" -> {
                    val part = (m["part"] as? Number)?.toInt() ?: return
                    expectedParts = (m["parts"] as? Number)?.toInt() ?: 1
                    deviceId = m["dev"] as? String
                    offerParts[part] = (m["files"] as? List<Map<String, Any?>>).orEmpty()
                    if (offerParts.size == expectedParts) onFullOffer()
                }
                "file" -> {
                    val i = (m["i"] as? Number)?.toInt() ?: return
                    val p = (m["p"] as? Number)?.toLong() ?: return
                    if (i !in files.indices) return
                    indexByPayload[p] = i
                    if (finishedEarly.remove(p)) saveLater(i, p)
                }
                "skip" -> {
                    val i = (m["i"] as? Number)?.toInt() ?: return
                    if (i in files.indices) finishFile(i, false, "The sender couldn't read this file", null, tellSender = false)
                }
                "cancel" -> {
                    cancelPayloads()
                    if (!answered) receiveListener?.onOfferWithdrawn(endpointId)
                    end(completed = false, cancelledByPeer = true, reason = null)
                }
            }
        }

        private fun onFullOffer() {
            val all = (0 until expectedParts).flatMap { offerParts[it].orEmpty() }.sortedBy { (it["i"] as? Number)?.toInt() ?: 0 }
            files = all.map {
                FileEntry(
                    SafePath.sanitizeOrDefault(it["n"] as? String ?: "file"),
                    (it["s"] as? Number)?.toLong() ?: 0L,
                    it["m"] as? String ?: "application/octet-stream",
                )
            }
            val offer = NearbyOffer(endpointId, senderName, deviceId, digits, files)
            val l = receiveListener
            if (l == null) {
                runCatching { sendJson(endpointId, mapOf("t" to "decline", "reason" to "Not receiving")) }
                end(false, false, "Not receiving")
                return
            }
            l.onOffer(offer) { yes ->
                answered = true
                if (ended) return@onOffer
                if (yes) {
                    accepted = true
                    l.onStarted(offer)
                    sendJson(endpointId, mapOf("t" to "accept"))
                    if (files.isEmpty()) end(true, false, null)
                } else {
                    sendJson(endpointId, mapOf("t" to "decline", "reason" to "Declined"))
                    end(false, false, "Declined")
                }
            }
        }

        private fun saveLater(i: Int, payloadId: Long) {
            val p = payloads[payloadId] ?: return
            io.execute { save(i, p) }
        }

        /** Copies a received file into Download/DropLink (or the chosen folder), then removes Nearby's copy. */
        private fun save(i: Int, payload: Payload) {
            val f = payload.asFile() ?: return finishFile(i, false, "Empty file payload", null)
            val entry = files[i]
            var sink: FileSink? = null
            try {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) f.asUri() else null
                val pfd = f.asParcelFileDescriptor()
                val input: InputStream = when {
                    uri != null -> appContext.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the received file")
                    pfd != null -> ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    else -> @Suppress("DEPRECATION") f.asJavaFile()?.let { FileInputStream(it) } ?: throw IOException("Received file not found")
                }
                val out = sinks().open(sessionKey, entry, null, 0)
                sink = out
                var total = 0L
                input.use { inp ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                }
                if (total != entry.size) throw IOException("Incomplete file ($total of ${entry.size} bytes)")
                val location = out.commit()
                sink = null
                // Remove Nearby's temporary copy so the file isn't stored twice.
                runCatching { if (uri != null) appContext.contentResolver.delete(uri, null, null) }
                @Suppress("DEPRECATION") runCatching { if (uri == null) f.asJavaFile()?.delete() }
                finishFile(i, true, null, location)
            } catch (e: Exception) {
                DiagLog.log("Nearby: saving ${entry.name} failed", e)
                runCatching { sink?.discard() }
                finishFile(i, false, e.message ?: "Couldn't save the file", null)
            } finally {
                payloads.remove(payload.id)
            }
        }

        @Synchronized
        private fun finishFile(i: Int, ok: Boolean, reason: String?, location: String?, tellSender: Boolean = true) {
            if (ended || results.containsKey(i)) return
            results[i] = ok
            receiveListener?.onFileDone(i, ok, reason, location)
            if (tellSender) runCatching { sendJson(endpointId, mapOf("t" to "saved", "i" to i.toLong(), "ok" to ok, "r" to reason)) }
            if (results.size == files.size) end(completed = true, cancelledByPeer = false, reason = null)
        }

        fun onDisconnected() {
            if (!ended) {
                if (!answered) receiveListener?.onOfferWithdrawn(endpointId)
                end(completed = accepted && results.size == files.size, cancelledByPeer = false, reason = "Connection lost")
            }
        }

        @Synchronized
        fun end(completed: Boolean, cancelledByPeer: Boolean, reason: String?) {
            if (ended) return
            ended = true
            if (incoming === this) incoming = null
            if (accepted) receiveListener?.onEnded(completed, cancelledByPeer, if (completed) null else reason)
            // Let the last "saved" message go out before hanging up.
            io.execute {
                runCatching { Thread.sleep(1_500) }
                runCatching { client.disconnectFromEndpoint(endpointId) }
            }
        }
    }

    // ---------------------------------------------------------------------------------- helpers

    private fun sendJson(endpointId: String, msg: Map<String, Any?>) {
        client.sendPayload(endpointId, Payload.fromBytes(Json.stringify(msg).toByteArray(Charsets.UTF_8)))
    }

    private fun parse(bytes: ByteArray?): Map<String, Any?>? =
        bytes?.let { runCatching { Json.parseObject(String(it, Charsets.UTF_8)) }.getOrNull() }

    companion object {
        const val SERVICE_ID = "app.droplink.v2"
        /** Point-to-point: one sender ↔ one receiver, with the highest-bandwidth upgrades (Wi-Fi hotspot/Direct). */
        val STRATEGY: Strategy = Strategy.P2P_POINT_TO_POINT
        private const val WINDOW = 3
        private const val IDLE_TIMEOUT = 90_000L
        private const val SAVE_TIMEOUT = 600_000L

        /** Human-readable reason for a Nearby status code (for the diagnostic log and errors). */
        fun describe(code: Int): String = "${ConnectionsStatusCodes.getStatusCodeString(code)} ($code)"
    }
}

/** Awaits a Play services Task; returns null on success or the status code of the failure. */
private suspend fun <T> Task<T>.awaitError(): Int? = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        if (!cont.isActive) return@addOnCompleteListener
        if (t.isSuccessful) cont.resume(null)
        else cont.resume((t.exception as? ApiException)?.statusCode ?: ConnectionsStatusCodes.STATUS_ERROR)
    }
}
