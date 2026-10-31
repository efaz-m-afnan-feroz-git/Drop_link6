package app.droplink.core.protocol

import java.io.IOException

/** Thrown for malformed data from the other phone. */
open class ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** One file in a transfer. [path] is a relative path using '/' separators (folders are preserved). */
data class FileEntry(val path: String, val size: Long, val mime: String) {
    val name: String get() = path.substringAfterLast('/')
}

/** Shared constants. */
object Protocol {
    const val BUFFER_SIZE = 1 shl 20 // 1 MiB streaming buffer
}

/**
 * Validates and normalises a peer-supplied relative path so a malicious sender can never write
 * outside the destination folder: no absolute paths, no '..', no characters that are illegal on
 * FAT/exFAT SD cards.
 */
object SafePath {
    private const val MAX_SEGMENT = 120
    private const val MAX_DEPTH = 16
    private val illegal = Regex("[\\\\:*?\"<>|\\u0000-\\u001F\\u007F]")

    fun sanitize(raw: String): String {
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith("/")) throw ProtocolException("Absolute paths are not allowed")
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw ProtocolException("Empty path")
        if (segments.size > MAX_DEPTH) throw ProtocolException("Path too deep")
        val clean = segments.map { seg ->
            if (seg == "." || seg == "..") throw ProtocolException("Path traversal is not allowed")
            val s = seg.replace(illegal, "_").trim().trimEnd('.')
            if (s.isEmpty()) "_" else if (s.length > MAX_SEGMENT) shorten(s) else s
        }
        return clean.joinToString("/")
    }

    /** Like [sanitize] but never throws: falls back to a safe single name. */
    fun sanitizeOrDefault(raw: String, default: String = "file"): String =
        runCatching { sanitize(raw) }.getOrElse { runCatching { sanitize(raw.substringAfterLast('/').substringAfterLast('\\')) }.getOrDefault(default) }

    private fun shorten(s: String): String {
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0 && s.length - dot <= 10) s.substring(dot) else ""
        return s.take(MAX_SEGMENT - ext.length) + ext
    }
}
