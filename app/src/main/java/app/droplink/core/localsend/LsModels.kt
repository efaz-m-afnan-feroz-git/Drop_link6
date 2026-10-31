package app.droplink.core.localsend

import app.droplink.core.protocol.Json
import app.droplink.core.protocol.ProtocolException

/**
 * LocalSend protocol v2 (https://github.com/localsend/protocol) data types.
 * DropLink speaks this open protocol so it works on every ROM without Google services, and also
 * with the official LocalSend app on Android, iPhone, Windows, macOS and Linux.
 */
object LocalSend {
    const val VERSION = "2.1"
    const val PORT = 53317
    const val MULTICAST_GROUP = "224.0.0.167"
    const val API = "/api/localsend/v2"
}

data class LsDevice(
    val alias: String,
    val version: String = LocalSend.VERSION,
    val deviceModel: String? = null,
    val deviceType: String? = "mobile",
    val fingerprint: String,
    val port: Int = LocalSend.PORT,
    val protocol: String = "https",
    val download: Boolean = false,
    /** Where we saw it (not part of the JSON). */
    val ip: String? = null,
) {
    val https: Boolean get() = protocol != "http"

    fun toJson(announce: Boolean? = null): Map<String, Any?> = buildMap {
        put("alias", alias)
        put("version", version)
        put("deviceModel", deviceModel)
        put("deviceType", deviceType)
        put("fingerprint", fingerprint)
        put("port", port.toLong())
        put("protocol", protocol)
        put("download", download)
        if (announce != null) put("announce", announce)
    }

    companion object {
        fun fromJson(m: Map<String, Any?>, ip: String? = null): LsDevice {
            val alias = (m["alias"] as? String)?.filter { !it.isISOControl() }?.take(64)?.ifBlank { null }
                ?: throw ProtocolException("Missing alias")
            val port = (m["port"] as? Number)?.toInt()?.takeIf { it in 1..65535 } ?: LocalSend.PORT
            return LsDevice(
                alias = alias,
                version = m["version"] as? String ?: "2.0",
                deviceModel = (m["deviceModel"] as? String)?.take(64),
                deviceType = (m["deviceType"] as? String)?.take(16),
                fingerprint = (m["fingerprint"] as? String)?.take(128) ?: "",
                port = port,
                protocol = if ((m["protocol"] as? String) == "http") "http" else "https",
                download = m["download"] as? Boolean ?: false,
                ip = ip,
            )
        }

        fun parse(text: String, ip: String? = null) = fromJson(Json.parseObject(text), ip)
    }
}

data class LsFile(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val sha256: String? = null,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "id" to id, "fileName" to fileName, "size" to size, "fileType" to fileType, "sha256" to sha256, "preview" to null,
    )

    companion object {
        fun fromJson(m: Map<String, Any?>): LsFile {
            val id = (m["id"] as? String)?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: throw ProtocolException("Missing file id")
            val size = (m["size"] as? Number)?.toLong()?.takeIf { it >= 0 } ?: throw ProtocolException("Missing file size")
            return LsFile(
                id = id,
                fileName = (m["fileName"] as? String)?.ifBlank { null } ?: throw ProtocolException("Missing file name"),
                size = size,
                fileType = (m["fileType"] as? String)?.take(255)?.ifBlank { null } ?: "application/octet-stream",
                sha256 = (m["sha256"] as? String)?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) },
            )
        }
    }
}

data class LsPrepareRequest(val info: LsDevice, val files: List<LsFile>) {
    fun toJson(): Map<String, Any?> = mapOf(
        "info" to info.toJson(),
        "files" to files.associate { it.id to it.toJson() },
    )

    companion object {
        const val MAX_FILES = 20_000

        @Suppress("UNCHECKED_CAST")
        fun parse(text: String, ip: String?): LsPrepareRequest {
            val m = Json.parseObject(text)
            val info = LsDevice.fromJson(m["info"] as? Map<String, Any?> ?: throw ProtocolException("Missing info"), ip)
            val files = (m["files"] as? Map<String, Any?> ?: throw ProtocolException("Missing files")).values.map {
                LsFile.fromJson(it as? Map<String, Any?> ?: throw ProtocolException("Bad file entry"))
            }
            if (files.size > MAX_FILES) throw ProtocolException("Too many files")
            return LsPrepareRequest(info, files)
        }
    }
}

data class LsPrepareResponse(val sessionId: String, val tokens: Map<String, String>) {
    fun toJson(): Map<String, Any?> = mapOf("sessionId" to sessionId, "files" to tokens)

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(text: String): LsPrepareResponse {
            val m = Json.parseObject(text)
            val sid = m["sessionId"] as? String ?: throw ProtocolException("Missing sessionId")
            val files = (m["files"] as? Map<String, Any?> ?: emptyMap()).mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
            return LsPrepareResponse(sid, files)
        }
    }
}
