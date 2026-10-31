package app.droplink.core.localsend

import app.droplink.core.protocol.Json
import app.droplink.core.protocol.Protocol
import app.droplink.core.security.Identity
import app.droplink.core.security.Tls
import app.droplink.core.transfer.CancelToken
import app.droplink.core.transfer.OutgoingFile
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

sealed class PrepareResult {
    data class Accepted(val response: LsPrepareResponse) : PrepareResult()
    data object NothingNeeded : PrepareResult()
    data object Declined : PrepareResult()
    data object Busy : PrepareResult()
    data object PinRequired : PrepareResult()
    data class Failed(val code: Int, val message: String) : PrepareResult()
}

sealed class UploadResult {
    data object Ok : UploadResult()
    data object ChecksumMismatch : UploadResult()
    data object Cancelled : UploadResult()
    data class Failed(val message: String) : UploadResult()
}

/** LocalSend v2 sender (HTTP client). Blocking calls: use a background thread. */
class LsClient(identity: Identity?, private val selfInfo: () -> LsDevice) {
    private val sslFactory: SSLSocketFactory = Tls.trustAllClient(identity).socketFactory

    private fun open(device: LsDevice, path: String, connectTimeout: Int, readTimeout: Int): HttpURLConnection =
        open(device.ip ?: throw IOException("Unknown address"), device.port, device.https, path, connectTimeout, readTimeout)

    private fun open(ip: String, port: Int, https: Boolean, path: String, connectTimeout: Int, readTimeout: Int): HttpURLConnection {
        val host = if (':' in ip) "[$ip]" else ip
        val url = java.net.URI("${if (https) "https" else "http"}://$host:$port${LocalSend.API}$path").toURL()
        val c = url.openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) {
            c.sslSocketFactory = sslFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true } // local devices have self-signed certs
        }
        c.connectTimeout = connectTimeout
        c.readTimeout = readTimeout
        c.useCaches = false
        return c
    }

    private fun postJson(c: HttpURLConnection, json: String): Int {
        val body = json.toByteArray(Charsets.UTF_8)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        return c.responseCode
    }

    private fun readBody(c: HttpURLConnection): String =
        runCatching { (if (c.responseCode < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull().orEmpty()

    /** Discovery handshake: tells [ip] about us and returns its info, or null if nobody answers. */
    fun register(ip: String, port: Int, https: Boolean, timeoutMs: Int = 2_000): LsDevice? = try {
        val c = open(ip, port, https, "/register", timeoutMs, timeoutMs * 2)
        try {
            if (postJson(c, Json.stringify(selfInfo().toJson())) == 200) {
                LsDevice.parse(readBody(c), ip).copy(port = port, protocol = if (https) "https" else "http")
            } else {
                null
            }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** Asks the receiver to accept [files]. Blocks while its user decides (up to ~2 minutes). */
    fun prepareUpload(target: LsDevice, files: List<LsFile>, cancel: CancelToken? = null): PrepareResult {
        val c = open(target, "/prepare-upload", 8_000, 150_000)
        val closer = Closeable { c.disconnect() }
        cancel?.register(closer)
        return try {
            val code = postJson(c, Json.stringify(LsPrepareRequest(selfInfo(), files).toJson()))
            when (code) {
                200 -> PrepareResult.Accepted(LsPrepareResponse.parse(readBody(c)))
                204 -> PrepareResult.NothingNeeded
                401 -> PrepareResult.PinRequired
                403 -> PrepareResult.Declined
                409, 429 -> PrepareResult.Busy
                else -> PrepareResult.Failed(code, readBody(c).take(200))
            }
        } catch (e: IOException) {
            if (cancel?.isCancelled == true) PrepareResult.Failed(-1, "Cancelled") else PrepareResult.Failed(-1, e.message ?: e.javaClass.simpleName)
        } finally {
            cancel?.unregister(closer)
            c.disconnect()
        }
    }

    /** Streams one file. [onProgress] gets the bytes sent so far. */
    fun upload(
        target: LsDevice,
        sessionId: String,
        file: LsFile,
        token: String,
        source: OutgoingFile,
        cancel: CancelToken,
        onProgress: (Long) -> Unit,
    ): UploadResult {
        val q = "?sessionId=${enc(sessionId)}&fileId=${enc(file.id)}&token=${enc(token)}"
        val c = open(target, "/upload$q", 8_000, 120_000)
        val closer = Closeable { c.disconnect() }
        cancel.register(closer)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(file.size)
            val buf = ByteArray(Protocol.BUFFER_SIZE)
            var sent = 0L
            var lastReport = 0L
            source.open().use { input ->
                c.outputStream.use { out ->
                    while (sent < file.size) {
                        cancel.throwIfCancelled()
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), file.size - sent).toInt())
                        if (n < 0) throw IOException("${file.fileName} got shorter while sending")
                        out.write(buf, 0, n)
                        sent += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 150) {
                            lastReport = now
                            onProgress(sent)
                        }
                    }
                }
            }
            onProgress(sent)
            return when (val code = c.responseCode) {
                200, 204 -> UploadResult.Ok
                422 -> UploadResult.ChecksumMismatch
                else -> UploadResult.Failed("Receiver answered $code")
            }
        } catch (e: Exception) {
            return if (cancel.isCancelled) UploadResult.Cancelled else UploadResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            cancel.unregister(closer)
            c.disconnect()
        }
    }

    fun cancel(target: LsDevice, sessionId: String) {
        runCatching {
            val c = open(target, "/cancel?sessionId=${enc(sessionId)}", 3_000, 3_000)
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
                c.responseCode
            } finally {
                c.disconnect()
            }
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
