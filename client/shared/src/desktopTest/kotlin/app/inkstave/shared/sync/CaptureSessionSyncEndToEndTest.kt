package app.inkstave.shared.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.inkstave.shared.format.SmpkReader
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.index.InkstaveDatabase
import app.inkstave.shared.index.LibraryIndexRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves the actual headline flow this integration exists for: a capture session, discovered and
 * sent by [CaptureSessionSender] the way a real Android send action would drive it, arrives on a
 * real [SyncServer], is imported by [CaptureSessionReceiver] via the exact same
 * [LibraryImporter.importImages] path M1's local import already uses, and is readable back out
 * via [SmpkReader] -- composing every primitive the four prior sync commits already built and
 * tested individually (discovery, pairing-established trust, the transport), not introducing any
 * new one.
 *
 * Same honest ceiling as [LanSyncTransportIntegrationTest]/[PairingSessionIntegrationTest]: two
 * real device identities, real JmDNS discovery, and a real loopback TLS connection on one
 * machine -- never a real phone and a real desktop on a real LAN.
 */
class CaptureSessionSyncEndToEndTest {
    private lateinit var senderDirectory: File
    private lateinit var receiverDirectory: File
    private lateinit var libraryDirectory: File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var index: LibraryIndexRepository
    private lateinit var importer: LibraryImporter

    @BeforeTest
    fun setUp() {
        senderDirectory = createTempDirectory("capture-sync-sender-").toFile()
        receiverDirectory = createTempDirectory("capture-sync-receiver-").toFile()
        libraryDirectory = createTempDirectory("capture-sync-library-").toFile()
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        InkstaveDatabase.Schema.create(driver)
        index = LibraryIndexRepository(driver)
        importer = LibraryImporter(libraryDirectory, index)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        senderDirectory.deleteRecursively()
        receiverDirectory.deleteRecursively()
        libraryDirectory.deleteRecursively()
    }

    /** Same real-certificate-fingerprint helper [LanSyncTransportIntegrationTest] uses, to simulate the trust
     * state a real pairing exchange would have left behind without re-driving [PairingSession] here (that
     * exchange is [PairingSessionIntegrationTest]'s job, not this test's). */
    private fun realFingerprintOf(settingsDirectory: File): String {
        getOrCreateDeviceIdentity(settingsDirectory)
        val metadata = Json.parseToJsonElement(File(settingsDirectory, "device-identity.json").readText()).jsonObject
        val password = metadata["keystorePassword"]!!.jsonPrimitive.content
        val keyStore =
            KeyStore.getInstance("PKCS12").apply {
                File(settingsDirectory, "device-identity.p12").inputStream().use { load(it, password.toCharArray()) }
            }
        return CertificateFingerprint.sha256(keyStore.getCertificate("inkstave") as X509Certificate)
    }

    private fun samplePngBytes(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    @Test
    fun `a capture session sent via discovery arrives, gets imported, and reads back correctly`() {
        val senderIdentity = getOrCreateDeviceIdentity(senderDirectory).let { it.copy(displayName = "Phone") }
        val receiverIdentity = getOrCreateDeviceIdentity(receiverDirectory).let { it.copy(displayName = "Desktop") }

        // The trust state a real pairing exchange would have left behind.
        val senderTrustStore = PeerTrustStore(File(senderDirectory, "trusted-peers.json"))
        val receiverPeer = TrustedPeer(receiverIdentity.deviceId, receiverIdentity.displayName, realFingerprintOf(receiverDirectory))
        senderTrustStore.add(receiverPeer)
        val receiverTrustStore = PeerTrustStore(File(receiverDirectory, "trusted-peers.json"))
        receiverTrustStore.add(TrustedPeer(senderIdentity.deviceId, senderIdentity.displayName, realFingerprintOf(senderDirectory)))

        // The receiving ("desktop") side: exactly what Main.kt's persistent listener does -- advertise,
        // accept one connection, hand it to CaptureSessionReceiver.
        val receiverDiscovery = JmDnsSyncDiscovery.create()
        val server = SyncServer(receiverDirectory, receiverTrustStore)
        receiverDiscovery.advertise(receiverIdentity, server.boundPort, setOf(DeviceRole.PROCESSING))

        var importedManifestId: String? = null
        var receiverFailure: Throwable? = null
        val serverThread =
            Thread {
                try {
                    server.acceptOne().use { connection ->
                        // null: this test proves the transport/import composition (this file's own
                        // scope, unchanged by the processing-pipeline slice), not the pipeline
                        // integration itself -- see CaptureSessionProcessingIntegrationTest for that.
                        importedManifestId =
                            CaptureSessionReceiver.receiveAndImport(connection, importer, processingClient = null).manifest.id
                    }
                } catch (t: Throwable) {
                    receiverFailure = t
                }
            }.apply { start() }

        // The sending ("phone") side: exactly what a real Android "send to desktop" action does --
        // its own short-lived discovery instance, CaptureSessionSender.
        val senderDiscovery = JmDnsSyncDiscovery.create()
        try {
            val sender = CaptureSessionSender(senderDiscovery, LanSyncTransport(senderDirectory, senderTrustStore))
            val outcome =
                sender.send(
                    peer = receiverPeer,
                    scoreTitle = "Moonlight Sonata",
                    photos = listOf(samplePngBytes(40, 60), samplePngBytes(40, 60), samplePngBytes(40, 60)),
                    discoveryTimeoutMillis = DISCOVERY_TIMEOUT_MILLIS,
                )
            assertIs<CaptureSessionSendOutcome.Sent>(outcome, "send outcome: $outcome")
        } finally {
            senderDiscovery.close()
        }

        serverThread.join(SERVER_JOIN_TIMEOUT_MILLIS)
        server.close()
        receiverDiscovery.close()

        assertEquals(null, receiverFailure, "receiving side must not throw: $receiverFailure")
        val manifestId = importedManifestId ?: error("receiving side never completed an import")

        // "See it in the library" (the receiving/desktop device's own library, per this
        // integration's scope) -- indexed immediately, not by a filesystem scan (ADR-0005).
        val libraryRow = index.listAll().singleOrNull { it.id == manifestId }
        assertTrue(libraryRow != null, "a session received over sync must appear in the receiving device's library index")
        assertEquals("Moonlight Sonata", libraryRow.title)

        // Readable back out exactly like any other imported score.
        SmpkReader(File(libraryRow.filePath)).use { reader ->
            assertEquals(3, reader.readPart("part-1").pageOrder.size)
        }
    }

    @Test
    fun `a peer that isn't currently discoverable is reported as not found, not a crash`() {
        val senderIdentity = getOrCreateDeviceIdentity(senderDirectory)
        val phantomPeer = TrustedPeer("nobody-on-the-network", "Phantom Desktop", "irrelevant-fingerprint")
        val senderTrustStore = PeerTrustStore(File(senderDirectory, "trusted-peers.json"))
        senderTrustStore.add(phantomPeer)

        val discovery = JmDnsSyncDiscovery.create()
        try {
            // Nothing on the network advertises "nobody-on-the-network" -- a real JmDNS browse, genuinely
            // searching, that genuinely finds nothing, not a fake standing in for that outcome.
            val sender = CaptureSessionSender(discovery, LanSyncTransport(senderDirectory, senderTrustStore))
            val outcome =
                sender.send(
                    peer = phantomPeer,
                    scoreTitle = "Irrelevant",
                    photos = emptyList(),
                    discoveryTimeoutMillis = SHORT_DISCOVERY_TIMEOUT_MILLIS,
                )
            assertEquals(CaptureSessionSendOutcome.PeerNotFound(phantomPeer), outcome)
        } finally {
            discovery.close()
        }
        // Referenced only to confirm identity provisioning itself doesn't interfere with this path.
        check(senderIdentity.deviceId.isNotBlank())
    }

    private companion object {
        const val SERVER_JOIN_TIMEOUT_MILLIS = 15_000L
        const val DISCOVERY_TIMEOUT_MILLIS = 10_000L
        const val SHORT_DISCOVERY_TIMEOUT_MILLIS = 1_500L
    }
}
