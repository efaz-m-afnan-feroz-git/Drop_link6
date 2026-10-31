package app.droplink.transfer

import android.content.Context
import android.os.Build
import app.droplink.core.localsend.LsDecision
import app.droplink.core.localsend.LsDevice
import app.droplink.core.localsend.LsFile
import app.droplink.core.localsend.LsIncomingSession
import app.droplink.core.localsend.LsServer
import app.droplink.core.localsend.LsServerCallbacks
import app.droplink.core.localsend.LsClient
import app.droplink.core.localsend.PrepareResult
import app.droplink.core.localsend.UploadResult
import app.droplink.core.security.IdentityStore
import app.droplink.core.transfer.CancelToken
import app.droplink.core.transfer.DirectorySinkFactory
import app.droplink.core.transfer.LocalOutgoingFile
import app.droplink.util.DiagLog
import java.io.File
import java.net.InetAddress
import java.util.Random

/**
 * Sends 32 MB from this phone to itself through the real LocalSend server and client (HTTPS on
 * 127.0.0.1), so a broken TLS/socket stack or storage problem shows up without a second phone.
 */
object SelfTest {
    data class Result(val ok: Boolean, val bytesPerSecond: Long, val detail: String?)

    private const val SIZE = 32L * 1024 * 1024

    fun run(context: Context, identityStore: IdentityStore): Result {
        val dir = File(context.cacheDir, "selftest").apply { deleteRecursively(); mkdirs() }
        val outDir = File(dir, "out")
        var server: LsServer? = null
        return try {
            val identity = identityStore.get()
            val src = File(dir, "selftest.bin")
            src.outputStream().buffered(1 shl 20).use { out ->
                val buf = ByteArray(1 shl 20)
                val rnd = Random(42)
                repeat((SIZE / buf.size).toInt()) { rnd.nextBytes(buf); out.write(buf) }
            }
            val me = LsDevice(alias = "self-test", deviceModel = Build.MODEL, fingerprint = identity.fingerprint, protocol = "https")
            val srv = LsServer(
                identity, { me }, { DirectorySinkFactory(outDir) },
                object : LsServerCallbacks {
                    override fun onPrepareUpload(session: LsIncomingSession) = LsDecision.Accept(session.files.map { it.id }.toSet())
                },
                preferredPort = 0,
                bindAddress = InetAddress.getByName("127.0.0.1"),
            )
            server = srv
            val port = srv.start()
            val target = me.copy(ip = "127.0.0.1", port = port)
            val client = LsClient(identity) { me.copy(alias = "self-test sender", fingerprint = "self-test-sender") }
            val file = LsFile("1", "selftest.bin", SIZE, "application/octet-stream")
            val prepared = client.prepareUpload(target, listOf(file))
            if (prepared !is PrepareResult.Accepted) return Result(false, 0, "prepare-upload: $prepared")
            val token = prepared.response.tokens["1"] ?: return Result(false, 0, "No upload token")
            val start = System.nanoTime()
            val up = client.upload(target, prepared.response.sessionId, file, token, LocalOutgoingFile(src), CancelToken()) {}
            val seconds = (System.nanoTime() - start) / 1e9
            if (up != UploadResult.Ok) return Result(false, 0, "upload: $up")
            val received = File(outDir, "selftest.bin")
            if (!received.isFile || received.length() != SIZE) return Result(false, 0, "Received file is ${received.length()} bytes")
            val speed = (SIZE / seconds.coerceAtLeast(0.001)).toLong()
            DiagLog.log("Self-test passed: ${"%.1f".format(java.util.Locale.ROOT, speed / 1_048_576.0)} MiB/s over loopback HTTPS")
            Result(true, speed, null)
        } catch (e: Exception) {
            DiagLog.log("Self-test failed", e)
            Result(false, 0, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { server?.stop() }
            dir.deleteRecursively()
        }
    }
}
