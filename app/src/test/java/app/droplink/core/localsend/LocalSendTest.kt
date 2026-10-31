package app.droplink.core.localsend

import app.droplink.core.protocol.Checksums
import app.droplink.core.protocol.FileEntry
import app.droplink.core.protocol.Json
import app.droplink.core.protocol.SafePath
import app.droplink.core.security.IdentityFactory
import app.droplink.core.transfer.CancelToken
import app.droplink.core.transfer.DirectorySinkFactory
import app.droplink.core.transfer.LocalOutgoingFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Socket
import java.util.Random
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LsModelTest {
    @Test
    fun deviceRoundTrip() {
        val d = LsDevice("Nice Orange", deviceModel = "Samsung", fingerprint = "abc", port = 1234, protocol = "http")
        val back = LsDevice.parse(Json.stringify(d.toJson(announce = true)), "10.0.0.2")
        assertEquals(d.copy(ip = "10.0.0.2"), back)
    }

    @Test
    fun parsesOfficialPrepareUploadExample() {
        // Shape taken from the LocalSend protocol README.
        val json = """
            {"info":{"alias":"Nice Orange","version":"2.0","deviceModel":"Samsung","deviceType":"mobile",
              "fingerprint":"random string","port":53317,"protocol":"https","download":true},
             "files":{"some file id":{"id":"some file id","fileName":"my image.png","size":324242,
               "fileType":"image/jpeg","sha256":null,"preview":null,"metadata":{"modified":"2021-01-01T12:34:56Z"}}}}
        """.trimIndent()
        val r = LsPrepareRequest.parse(json, "1.2.3.4")
        assertEquals("Nice Orange", r.info.alias)
        assertEquals(1, r.files.size)
        assertEquals(324242L, r.files[0].size)
        assertEquals("image/jpeg", r.files[0].fileType)
    }

    @Test
    fun httpParsesChunkedBodies() {
        val raw = "POST /x?a=1&b=two%20words HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
        val req = Http.readRequest(java.io.BufferedInputStream(ByteArrayInputStream(raw.toByteArray())))!!
        assertEquals("two words", req.query["b"])
        assertEquals("hello world", req.bodyText())
    }

    @Test
    fun fileNamesAreSanitized() {
        assertEquals("passwd", SafePath.sanitizeOrDefault("../../etc/passwd").substringAfterLast('/'))
        assertEquals("folder/pic.jpg", SafePath.sanitizeOrDefault("folder/pic.jpg"))
    }
}

/** Real HTTPS transfers between our LocalSend client and server over 127.0.0.1. */
class LocalSendTransferTest {
    private lateinit var root: File
    private val receiverId = IdentityFactory.generate("receiver")
    private val senderId = IdentityFactory.generate("sender")
    private val senderInfo = LsDevice("Sender", fingerprint = senderId.fingerprint)
    private val receiverInfo = LsDevice("Receiver", fingerprint = receiverId.fingerprint)
    private val finished = CopyOnWriteArrayList<Triple<String, Boolean, String?>>()
    private val registered = CopyOnWriteArrayList<LsDevice>()
    @Volatile private var decision: (LsIncomingSession) -> LsDecision = { LsDecision.Accept(it.files.map { f -> f.id }.toSet()) }
    private val ended = CopyOnWriteArrayList<LsOutcome>()
    private lateinit var server: LsServer
    private lateinit var target: LsDevice
    private val client = LsClient(senderId) { senderInfo }

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "ls-" + UUID.randomUUID()).apply { mkdirs() }
        server = newServer(https = true)
        val port = server.start()
        target = receiverInfo.copy(ip = "127.0.0.1", port = port)
    }

    private fun newServer(https: Boolean) = LsServer(
        if (https) receiverId else null, { receiverInfo }, { DirectorySinkFactory(File(root, "out")) },
        object : LsServerCallbacks {
            override fun onRegister(device: LsDevice) { registered += device }
            override fun onPrepareUpload(session: LsIncomingSession) = decision(session)
            override fun onFileFinished(sessionId: String, fileId: String, ok: Boolean, reason: String?, location: String?) {
                finished += Triple(fileId, ok, reason)
            }
            override fun onSessionEnded(sessionId: String, outcome: LsOutcome) { ended += outcome }
        },
        preferredPort = 0,
    )

    @After
    fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    private fun file(name: String, size: Int) =
        File(root, "src/$name").apply { parentFile.mkdirs(); writeBytes(ByteArray(size).also { Random(size.toLong()).nextBytes(it) }) }

    private fun lsFile(id: String, f: File, sha: Boolean = true) =
        LsFile(id, f.name, f.length(), "application/octet-stream", if (sha) Checksums.sha256Hex(f.readBytes()) else null)

    @Test
    fun registerExchangesInfo() {
        val d = client.register("127.0.0.1", target.port, https = true)
        assertNotNull(d)
        assertEquals("Receiver", d!!.alias)
        assertEquals("Sender", registered.single().alias)
    }

    @Test
    fun fullTransferWithChecksums() {
        val a = file("video.mp4", 5 * 1024 * 1024 + 17)
        val b = file("note.txt", 0)
        val files = listOf(lsFile("1", a), lsFile("2", b))
        val prep = client.prepareUpload(target, files) as PrepareResult.Accepted
        for (f in files) {
            val src = if (f.id == "1") a else b
            val r = client.upload(target, prep.response.sessionId, f, prep.response.tokens.getValue(f.id), LocalOutgoingFile(src), CancelToken()) {}
            assertEquals(UploadResult.Ok, r)
        }
        Thread.sleep(200)
        assertEquals(Checksums.sha256Hex(a.readBytes()), Checksums.sha256Hex(File(root, "out/video.mp4").readBytes()))
        assertTrue(File(root, "out/note.txt").exists())
        assertEquals("finished=$finished", listOf<LsOutcome>(LsOutcome.Completed), ended.toList())
    }

    @Test
    fun plainHttpWorksToo() {
        server.stop()
        server = newServer(https = false)
        val t = target.copy(port = server.start(), protocol = "http")
        val a = file("x.bin", 300_000)
        val prep = client.prepareUpload(t, listOf(lsFile("1", a))) as PrepareResult.Accepted
        assertEquals(UploadResult.Ok, client.upload(t, prep.response.sessionId, lsFile("1", a), prep.response.tokens.getValue("1"), LocalOutgoingFile(a), CancelToken()) {})
    }

    @Test
    fun declineAndBusy() {
        decision = { LsDecision.Decline("no") }
        assertEquals(PrepareResult.Declined, client.prepareUpload(target, listOf(lsFile("1", file("a", 10)))))
        decision = { LsDecision.Busy }
        assertEquals(PrepareResult.Busy, client.prepareUpload(target, listOf(lsFile("1", file("a", 10)))))
    }

    @Test
    fun secondSessionIsBlockedWhileOneIsOpen() {
        val f = file("a.bin", 1000)
        assertTrue(client.prepareUpload(target, listOf(lsFile("1", f))) is PrepareResult.Accepted)
        assertEquals(PrepareResult.Busy, client.prepareUpload(target, listOf(lsFile("1", f))))
    }

    @Test
    fun checksumMismatchIsRejected() {
        val f = file("a.bin", 5000)
        val wrong = lsFile("1", f).copy(sha256 = "00".repeat(32))
        val prep = client.prepareUpload(target, listOf(wrong)) as PrepareResult.Accepted
        assertEquals(UploadResult.ChecksumMismatch, client.upload(target, prep.response.sessionId, wrong, prep.response.tokens.getValue("1"), LocalOutgoingFile(f), CancelToken()) {})
        assertTrue(File(root, "out").listFiles().orEmpty().none { it.name.startsWith("a") })
    }

    @Test
    fun wrongTokenIsForbidden() {
        val f = file("a.bin", 100)
        val prep = client.prepareUpload(target, listOf(lsFile("1", f))) as PrepareResult.Accepted
        val r = client.upload(target, prep.response.sessionId, lsFile("1", f), "bad-token", LocalOutgoingFile(f), CancelToken()) {}
        assertTrue(r is UploadResult.Failed)
    }

    @Test
    fun senderCancelEndsTheSession() {
        val f = file("a.bin", 100)
        val prep = client.prepareUpload(target, listOf(lsFile("1", f))) as PrepareResult.Accepted
        client.cancel(target, prep.response.sessionId)
        Thread.sleep(200)
        assertEquals(listOf<LsOutcome>(LsOutcome.CancelledBySender), ended.toList())
    }

    @Test
    fun receiverCancelStopsAnUploadInFlight() {
        val f = file("big.bin", 40 * 1024 * 1024)
        val prep = client.prepareUpload(target, listOf(lsFile("1", f))) as PrepareResult.Accepted
        val started = CountDownLatch(1)
        val t = Thread {
            Thread.sleep(50)
            started.await(5, TimeUnit.SECONDS)
            server.cancelSession()
        }
        t.start()
        val r = client.upload(target, prep.response.sessionId, lsFile("1", f), prep.response.tokens.getValue("1"), LocalOutgoingFile(f), CancelToken()) {
            if (it > 1024 * 1024) started.countDown()
        }
        assertTrue("$r", r !is UploadResult.Ok)
        assertTrue(ended.contains(LsOutcome.CancelledHere))
    }

    @Test
    fun discoveryAnswersAnnouncementsWithRegister() {
        val peers = CopyOnWriteArrayList<LsDevice>()
        val disc = LsDiscovery({ senderInfo }, client, { peers += it }, port = 0)
        // Simulate the receiver's multicast announcement arriving from 127.0.0.1.
        disc.handle(Json.stringify(target.toJson(announce = true)), "127.0.0.1")
        val deadline = System.currentTimeMillis() + 5_000
        while (registered.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("Receiver", peers.single().alias)
        assertEquals("Sender", registered.single().alias) // we answered via POST /register
    }

    @Test
    fun keepAliveServesManyRequestsOnOneConnection() {
        val s = Socket("127.0.0.1", server.port)
        s.close() // plain TCP to an HTTPS port must not crash the server
        repeat(20) { assertNotNull(client.register("127.0.0.1", target.port, https = true)) }
    }

    @Suppress("unused")
    private fun entry(f: File) = FileEntry(f.name, f.length(), "application/octet-stream")
}
