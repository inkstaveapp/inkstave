package app.inkstave.shared.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Capture-session transfer over loopback TLS via [SyncServer]/[LanSyncTransport], between two real
 * identities that already trust each other's fingerprints (as pairing would leave them).
 */
class LanSyncTransportIntegrationTest {
    private lateinit var senderDirectory: java.io.File
    private lateinit var receiverDirectory: java.io.File

    @BeforeTest
    fun setUp() {
        senderDirectory = createTempDirectory("sync-sender-").toFile()
        receiverDirectory = createTempDirectory("sync-receiver-").toFile()
    }

    @AfterTest
    fun tearDown() {
        senderDirectory.deleteRecursively()
        receiverDirectory.deleteRecursively()
    }

    /** Fingerprint of the certificate generated for [settingsDirectory], as pairing would pin it. */
    private fun realFingerprintOf(settingsDirectory: java.io.File): String {
        getOrCreateDeviceIdentity(settingsDirectory) // ensure it's provisioned first
        val metadata = Json.parseToJsonElement(java.io.File(settingsDirectory, "device-identity.json").readText()).jsonObject
        val password = metadata["keystorePassword"]!!.jsonPrimitive.content
        val keyStore =
            KeyStore.getInstance("PKCS12").apply {
                java.io
                    .File(settingsDirectory, "device-identity.p12")
                    .inputStream()
                    .use { load(it, password.toCharArray()) }
            }
        return CertificateFingerprint.sha256(keyStore.getCertificate("inkstave") as X509Certificate)
    }

    @Test
    fun `a full capture session sent by the sender arrives on the receiver, in order, with exact photo bytes`() {
        val senderIdentity = getOrCreateDeviceIdentity(senderDirectory)
        val receiverIdentity = getOrCreateDeviceIdentity(receiverDirectory)

        // As pairing would leave it: each side trusts the other's fingerprint.
        val senderTrustStore = PeerTrustStore(java.io.File(senderDirectory, "trusted-peers.json"))
        senderTrustStore.add(TrustedPeer(receiverIdentity.deviceId, "Desktop", realFingerprintOf(receiverDirectory)))
        val receiverTrustStore = PeerTrustStore(java.io.File(receiverDirectory, "trusted-peers.json"))
        receiverTrustStore.add(TrustedPeer(senderIdentity.deviceId, "Phone", realFingerprintOf(senderDirectory)))

        val server = SyncServer(receiverDirectory, receiverTrustStore)
        val received = mutableListOf<CaptureSessionMessage>()
        val serverThread =
            Thread {
                server.acceptOne().use { connection ->
                    repeat(SESSION_MESSAGE_COUNT) { received.add(connection.receive()) }
                }
            }.apply { start() }

        val transport = LanSyncTransport(senderDirectory, senderTrustStore)
        val peer = TrustedPeer(receiverIdentity.deviceId, "Desktop", realFingerprintOf(receiverDirectory))
        val toSend =
            listOf(
                CaptureSessionMessage.SessionStart("session-1", "Moonlight Sonata"),
                CaptureSessionMessage.Photo("session-1", 0, ByteArray(4096) { it.toByte() }),
                CaptureSessionMessage.Photo("session-1", 1, ByteArray(4096) { (it * 3).toByte() }),
                CaptureSessionMessage.SessionEnd("session-1"),
            )
        transport.connect(peer, "127.0.0.1", server.boundPort).use { connection ->
            toSend.forEach(connection::send)
        }

        serverThread.join(SERVER_JOIN_TIMEOUT_MILLIS)
        server.close()

        assertEquals(toSend, received, "messages must arrive in the same order, with exact content, as they were sent")
    }

    @Test
    fun `an unpaired sender can never successfully exchange a message, even though its own connect() call can locally succeed`() {
        val receiverIdentity = getOrCreateDeviceIdentity(receiverDirectory)
        val receiverTrustStore = PeerTrustStore(java.io.File(receiverDirectory, "trusted-peers.json"))
        // Deliberately do NOT add the sender's fingerprint -- this is the "never paired" case.

        val server = SyncServer(receiverDirectory, receiverTrustStore)
        var serverFailed = false
        val serverThread =
            Thread {
                serverFailed =
                    try {
                        server.acceptOne()
                        false
                    } catch (e: IOException) {
                        true
                    }
            }.apply { start() }

        val senderTrustStore = PeerTrustStore(java.io.File(senderDirectory, "trusted-peers.json"))
        // The sender trusts the receiver, so what must fail is the receiver refusing the sender.
        senderTrustStore.add(TrustedPeer(receiverIdentity.deviceId, "Desktop", realFingerprintOf(receiverDirectory)))
        val transport = LanSyncTransport(senderDirectory, senderTrustStore)
        val peer = TrustedPeer(receiverIdentity.deviceId, "Desktop", realFingerprintOf(receiverDirectory))

        // Under TLS 1.3 the sender's connect() and even a small send() can succeed locally after the
        // receiver has already rejected the certificate (the write fits in the socket buffer). A
        // receive() reliably fails because the receiver has closed the connection.
        assertFailsWith<IOException> {
            transport.connect(peer, "127.0.0.1", server.boundPort).use { connection ->
                connection.send(CaptureSessionMessage.SessionStart("irrelevant-session", "Irrelevant"))
                connection.receive()
            }
        }

        serverThread.join(SERVER_JOIN_TIMEOUT_MILLIS)
        server.close()
        assertTrue(serverFailed, "the receiving side's TLS handshake must itself reject an unpaired sender's certificate")
    }

    private companion object {
        const val SESSION_MESSAGE_COUNT = 4
        const val SERVER_JOIN_TIMEOUT_MILLIS = 10_000L
    }
}
