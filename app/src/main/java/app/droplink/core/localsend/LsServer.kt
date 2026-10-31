package app.droplink.core.localsend

import app.droplink.core.protocol.Checksums
import app.droplink.core.protocol.FileEntry
import app.droplink.core.protocol.Json
import app.droplink.core.protocol.Protocol
import app.droplink.core.protocol.ProtocolException
import app.droplink.core.protocol.SafePath
import app.droplink.core.security.Identity
import app.droplink.core.security.Tls
import app.droplink.core.transfer.CoreLog
import app.droplink.core.transfer.FileSink
import app.droplink.core.transfer.SinkFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LsIncomingSession(val sessionId: String, val sender: LsDevice, val files: List<LsFile>)

sealed class LsDecision {
    data class Accept(val fileIds: Set<String>) : LsDecision()
    data class Decline(val reason: String) : LsDecision()
    data object Busy : LsDecision()
}

sealed class LsOutcome {
    data object Completed : LsOutcome()
    data object CancelledBySender : LsOutcome()
    data object CancelledHere : LsOutcome()
    data class Failed(val reason: String) : LsOutcome()
}

interface LsServerCallbacks {
    /** Another LocalSend device announced itself to us (discovery). */
    fun onRegister(device: LsDevice) {}
    /** Blocking: ask the user. Should return within ~2 minutes. */
    fun onPrepareUpload(session: LsIncomingSession): LsDecision
    fun onSessionStarted(session: LsIncomingSession, accepted: List<LsFile>) {}
    fun onFileProgress(sessionId: String, fileId: String, bytes: Long) {}
    fun onFileFinished(sessionId: String, fileId: String, ok: Boolean, reason: String?, location: String?) {}
    fun onSessionEnded(sessionId: String, outcome: LsOutcome) {}
}

/**
 * LocalSend v2 receiver: a small HTTPS (or HTTP) server implementing register, info,
 * prepare-upload, upload and cancel. One incoming session at a time, as the spec expects (409).
 */
class LsServer(
    private val identity: Identity?,
    private val selfInfo: () -> LsDevice,
    private val sinks: () -> SinkFactory,
    private val callbacks: LsServerCallbacks,
    private val preferredPort: Int = LocalSend.PORT,
    private val bindAddress: InetAddress? = null,
    private val idleTimeoutMs: Long = 120_000,
) {
    @Volatile private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: -1
    val isRunning: Boolean get() = server?.isClosed == false
    val https: Boolean get() = identity != null

    private inner class Session(
        val id: String,
        val senderIp: String?,
        val device: LsDevice,
        val files: Map<String, LsFile>,
        val tokens: Map<String, String>,
    ) {
        val done = ConcurrentHashMap<String, Boolean>()
        val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
        @Volatile var lastActivity = System.currentTimeMillis()
        @Volatile var ended = false
    }

    private val lock = Any()
    @Volatile private var session: Session? = null
    @Volatile private var preparing = false
    private val random = SecureRandom()

    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val ss: ServerSocket = if (identity != null) {
            val s = Tls.context(identity, expectedPeerFingerprint = null).serverSocketFactory.createServerSocket() as javax.net.ssl.SSLServerSocket
            s.reuseAddress = true
            bind(s)
            Tls.configure(s, requireClientCert = false)
            s
        } else {
            ServerSocket().also { it.reuseAddress = true; bind(it) }
        }
        server = ss
        Thread({ acceptLoop(ss) }, "ls-accept").apply { isDaemon = true }.start()
        Thread({ watchdog(ss) }, "ls-watchdog").apply { isDaemon = true }.start()
        CoreLog.log("LocalSend server on port ${ss.localPort} (${if (https) "https" else "http"})")
        return ss.localPort
    }

    private fun bind(s: ServerSocket) {
        runCatching { s.receiveBufferSize = 1 shl 20 }
        try {
            s.bind(InetSocketAddress(bindAddress, preferredPort), 16)
        } catch (e: BindException) {
            s.bind(InetSocketAddress(bindAddress, 0), 16) // 53317 taken (e.g. official LocalSend app running)
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        session?.let { end(it, LsOutcome.CancelledHere) }
    }

    /** Cancels the incoming transfer from this phone. */
    fun cancelSession() {
        session?.let { end(it, LsOutcome.CancelledHere) }
    }

    private fun end(s: Session, outcome: LsOutcome, except: Socket? = null) {
        synchronized(lock) {
            if (s.ended) return
            s.ended = true
            if (session === s) session = null
        }
        CoreLog.log("LocalSend session ${s.id} ended: $outcome")
        callbacks.onSessionEnded(s.id, outcome)
        // Abort uploads still in flight (cancel). Closing a TLS socket can block, so do it off-thread.
        val toClose = s.sockets.filter { it !== except }
        if (toClose.isNotEmpty()) {
            Thread({ toClose.forEach { runCatching { it.close() } } }, "ls-close").apply { isDaemon = true }.start()
        }
    }

    private fun watchdog(ss: ServerSocket) {
        while (!ss.isClosed) {
            runCatching { Thread.sleep(5_000) }
            val s = session ?: continue
            if (s.sockets.isEmpty() && System.currentTimeMillis() - s.lastActivity > idleTimeoutMs) {
                end(s, LsOutcome.Failed("The sending device stopped sending"))
            }
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: SocketException) {
                if (ss.isClosed) return else continue
            } catch (e: IOException) {
                continue
            }
            Thread({ serve(socket) }, "ls-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        val ip = socket.inetAddress?.hostAddress
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            if (socket is javax.net.ssl.SSLSocket) socket.startHandshake()
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
            while (!socket.isClosed) {
                val req = Http.readRequest(input) ?: break
                val keep = try {
                    route(req, ip, socket, output)
                } catch (e: ProtocolException) {
                    Http.writeResponse(output, 400, keepAlive = false)
                    false
                }
                if (!keep || !req.keepAlive) break
                req.drain()
            }
        } catch (e: IOException) {
            // client went away; nothing to do
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Returns false if the connection must be closed afterwards. */
    private fun route(req: HttpRequest, ip: String?, socket: Socket, out: BufferedOutputStream): Boolean {
        val path = req.path.removeSuffix("/")
        return when {
            path == "${LocalSend.API}/register" && req.method == "POST" -> {
                runCatching { callbacks.onRegister(LsDevice.parse(req.bodyText(64 * 1024), ip)) }
                Http.writeJson(out, 200, Json.stringify(selfInfo().toJson()))
                true
            }
            path == "${LocalSend.API}/info" && req.method == "GET" -> {
                Http.writeJson(out, 200, Json.stringify(selfInfo().toJson()))
                true
            }
            path == "${LocalSend.API}/prepare-upload" && req.method == "POST" -> {
                prepareUpload(req, ip, out)
                true
            }
            path == "${LocalSend.API}/upload" && req.method == "POST" -> upload(req, ip, socket, out)
            path == "${LocalSend.API}/cancel" && req.method == "POST" -> {
                val s = session
                if (s != null && s.id == req.query["sessionId"]) end(s, LsOutcome.CancelledBySender)
                Http.writeResponse(out, 200)
                true
            }
            else -> {
                Http.writeResponse(out, 404)
                true
            }
        }
    }

    private fun prepareUpload(req: HttpRequest, ip: String?, out: BufferedOutputStream) {
        val parsed = LsPrepareRequest.parse(req.bodyText(), ip)
        if (parsed.files.isEmpty()) {
            Http.writeResponse(out, 400)
            return
        }
        synchronized(lock) {
            if (preparing || session != null) {
                Http.writeResponse(out, 409)
                return
            }
            preparing = true
        }
        try {
            val sid = UUID.randomUUID().toString()
            val incoming = LsIncomingSession(sid, parsed.info, parsed.files)
            when (val d = callbacks.onPrepareUpload(incoming)) {
                is LsDecision.Decline -> Http.writeResponse(out, 403)
                LsDecision.Busy -> Http.writeResponse(out, 409)
                is LsDecision.Accept -> {
                    val accepted = parsed.files.filter { it.id in d.fileIds }
                    if (accepted.isEmpty()) {
                        Http.writeResponse(out, 204)
                        return
                    }
                    val tokens = accepted.associate { it.id to token() }
                    val s = Session(sid, ip, parsed.info, accepted.associateBy { it.id }, tokens)
                    synchronized(lock) { session = s }
                    callbacks.onSessionStarted(incoming, accepted)
                    Http.writeJson(out, 200, Json.stringify(LsPrepareResponse(sid, tokens).toJson()))
                }
            }
        } finally {
            synchronized(lock) { preparing = false }
        }
    }

    private fun token(): String = ByteArray(16).also { random.nextBytes(it) }.let { Checksums.hex(it) }

    private fun upload(req: HttpRequest, ip: String?, socket: Socket, out: BufferedOutputStream): Boolean {
        val s = session
        val sid = req.query["sessionId"]
        val fid = req.query["fileId"]
        val tok = req.query["token"]
        if (sid == null || fid == null || tok == null) {
            Http.writeResponse(out, 400, keepAlive = false)
            return false
        }
        if (s == null || s.ended || s.id != sid) {
            Http.writeResponse(out, 409, keepAlive = false)
            return false
        }
        val file = s.files[fid]
        if (file == null || s.tokens[fid] != tok || (s.senderIp != null && ip != s.senderIp)) {
            Http.writeResponse(out, 403, keepAlive = false)
            return false
        }
        if (s.done[fid] == true) {
            Http.writeResponse(out, 409, keepAlive = false)
            return false
        }
        if (req.contentLength >= 0 && req.contentLength != file.size) {
            Http.writeResponse(out, 400, keepAlive = false)
            return false
        }
        s.sockets += socket
        s.lastActivity = System.currentTimeMillis()
        socket.soTimeout = 60_000
        val entry = FileEntry(SafePath.sanitizeOrDefault(file.fileName), file.size, file.fileType)
        var sink: FileSink? = null
        try {
            sink = sinks().open(s.id, entry, null, 0)
            val digest = if (file.sha256 != null) Checksums.sha256() else null
            val buf = ByteArray(Protocol.BUFFER_SIZE)
            var total = 0L
            var lastReport = 0L
            while (true) {
                val n = req.body.read(buf)
                if (n < 0) break
                if (total + n > file.size) throw ProtocolException("More data than announced")
                sink.write(buf, 0, n)
                digest?.update(buf, 0, n)
                total += n
                val now = System.currentTimeMillis()
                if (now - lastReport > 150 || total == file.size) {
                    lastReport = now
                    s.lastActivity = now
                    callbacks.onFileProgress(s.id, fid, total)
                }
            }
            if (total != file.size) throw IOException("Incomplete file ($total of ${file.size} bytes)")
            if (digest != null && !Checksums.matches(file.sha256!!, Checksums.hex(digest.digest()))) {
                sink.discard()
                sink = null
                callbacks.onFileFinished(s.id, fid, false, "Checksum mismatch (SHA-256)", null)
                Http.writeResponse(out, 422, keepAlive = false)
                return false
            }
            val location = sink.commit()
            sink = null
            s.done[fid] = true
            callbacks.onFileFinished(s.id, fid, true, null, location)
            Http.writeResponse(out, 200)
            if (s.files.keys.all { s.done[it] == true }) end(s, LsOutcome.Completed, except = socket)
            return true
        } catch (e: Exception) {
            runCatching { sink?.discard() }
            if (!s.ended) {
                CoreLog.log("LocalSend upload of ${file.fileName} failed", e)
                callbacks.onFileFinished(s.id, fid, false, e.message ?: "Upload failed", null)
                runCatching { Http.writeResponse(out, 500, keepAlive = false) }
            }
            return false
        } finally {
            s.sockets -= socket
            s.lastActivity = System.currentTimeMillis()
        }
    }
}
