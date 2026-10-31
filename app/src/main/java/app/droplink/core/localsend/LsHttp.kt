package app.droplink.core.localsend

import app.droplink.core.protocol.ProtocolException
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** A minimal HTTP/1.1 request, enough for the LocalSend API (no multipart, no compression). */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
    val contentLength: Long,
    val keepAlive: Boolean,
) {
    fun bodyText(max: Int = 32 * 1024 * 1024): String {
        val bytes = body.readNBytesCompat(max + 1)
        if (bytes.size > max) throw ProtocolException("Request body too large")
        return String(bytes, Charsets.UTF_8)
    }

    /** Reads and discards what's left of the body, so the connection can serve the next request. */
    fun drain() {
        val buf = ByteArray(64 * 1024)
        @Suppress("ControlFlowWithEmptyBody")
        while (body.read(buf) >= 0) {}
    }
}

object Http {
    private const val MAX_HEADER = 32 * 1024

    /** Parses one request from [input], or returns null if the connection closed cleanly. */
    fun readRequest(input: BufferedInputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isEmpty()) return readRequest(input) // tolerate a stray CRLF between requests
        val parts = requestLine.split(' ')
        if (parts.size < 3) throw ProtocolException("Bad request line")
        val method = parts[0].uppercase()
        val target = parts[1]
        val http10 = parts[2].endsWith("/1.0")
        val headers = HashMap<String, String>()
        var total = requestLine.length
        while (true) {
            val line = readLine(input) ?: throw ProtocolException("Connection closed in headers")
            if (line.isEmpty()) break
            total += line.length
            if (total > MAX_HEADER) throw ProtocolException("Headers too large")
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val path = target.substringBefore('?')
        val query = if ('?' in target) parseQuery(target.substringAfter('?')) else emptyMap()
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val length = headers["content-length"]?.toLongOrNull() ?: if (chunked) -1L else 0L
        if (length < -1) throw ProtocolException("Bad Content-Length")
        val body: InputStream = when {
            chunked -> ChunkedInputStream(input)
            else -> BoundedInputStream(input, length)
        }
        val connection = headers["connection"]?.lowercase()
        val keepAlive = if (http10) connection == "keep-alive" else connection != "close"
        return HttpRequest(method, path, query, headers, body, length, keepAlive)
    }

    fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate { part ->
        val k = part.substringBefore('=')
        val v = part.substringAfter('=', "")
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    fun writeResponse(out: OutputStream, status: Int, body: ByteArray = ByteArray(0), contentType: String? = null, keepAlive: Boolean = true) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    fun writeJson(out: OutputStream, status: Int, json: String, keepAlive: Boolean = true) =
        writeResponse(out, status, json.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", keepAlive)

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 409 -> "Conflict"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"
        else -> "Error"
    }

    /** Reads a CRLF (or LF) terminated ASCII line; null at end of stream before any byte. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8 * 1024) throw ProtocolException("Line too long")
        }
    }

    internal fun readLinePublic(input: InputStream) = readLine(input)
}

/** Exposes exactly [length] bytes of [source]. */
class BoundedInputStream(private val source: InputStream, length: Long) : InputStream() {
    private var remaining = length
    override fun read(): Int {
        if (remaining <= 0) return -1
        val c = source.read()
        if (c < 0) throw IOException("Connection closed mid-body")
        remaining--
        return c
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw IOException("Connection closed mid-body")
        remaining -= n
        return n
    }
}

/** Decodes a chunked transfer-encoded body. */
class ChunkedInputStream(private val source: InputStream) : InputStream() {
    private var chunkLeft = 0L
    private var done = false

    private fun nextChunk(): Boolean {
        if (done) return false
        if (chunkLeft > 0) return true
        val line = Http.readLinePublic(source) ?: throw IOException("Connection closed in chunk header")
        val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw ProtocolException("Bad chunk size")
        if (size == 0L) {
            // trailers until empty line
            while (true) {
                val t = Http.readLinePublic(source) ?: break
                if (t.isEmpty()) break
            }
            done = true
            return false
        }
        chunkLeft = size
        return true
    }

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!nextChunk()) return -1
        val n = source.read(b, off, minOf(len.toLong(), chunkLeft).toInt())
        if (n < 0) throw IOException("Connection closed mid-chunk")
        chunkLeft -= n
        if (chunkLeft == 0L) Http.readLinePublic(source) // CRLF after the chunk data
        return n
    }
}

internal fun InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
