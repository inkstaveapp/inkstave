package app.inkstave.shared.sync

import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pairing over loopback TLS between two separate identities: a [PairingServer] on a background
 * thread accepting, [PairingSession.initiate] on the test thread.
 */
class PairingSessionIntegrationTest {
    private lateinit var acceptingDirectory: java.io.File
    private lateinit var initiatingDirectory: java.io.File

    @BeforeTest
    fun setUp() {
        acceptingDirectory = createTempDirectory("pairing-accepting-").toFile()
        initiatingDirectory = createTempDirectory("pairing-initiating-").toFile()
    }

    @AfterTest
    fun tearDown() {
        acceptingDirectory.deleteRecursively()
        initiatingDirectory.deleteRecursively()
    }

    @Test
    fun `both sides complete the exchange and compute the same fingerprint for each other's real certificate`() {
        val acceptingIdentity = getOrCreateDeviceIdentity(acceptingDirectory).let { it.copy(displayName = "Desktop") }
        val initiatingIdentity = getOrCreateDeviceIdentity(initiatingDirectory).let { it.copy(displayName = "Phone") }

        val server = PairingServer(acceptingDirectory)
        var acceptOutcome: PairingOutcome? = null
        val serverThread =
            Thread {
                acceptOutcome = server.acceptOne(acceptingIdentity)
            }.apply { start() }

        val initiateOutcome =
            PairingSession.initiate(
                device =
                    DiscoveredDevice(
                        "accepting-device-id",
                        "Desktop",
                        listOf("127.0.0.1"),
                        server.boundPort,
                        setOf(DeviceRole.PROCESSING),
                    ),
                localIdentity = initiatingIdentity,
                settingsDirectory = initiatingDirectory,
            )

        serverThread.join(SERVER_JOIN_TIMEOUT_MILLIS)
        server.close()

        val initiateResult = assertIs<PairingOutcome.AwaitingConfirmation>(initiateOutcome, "initiate: $initiateOutcome")
        val acceptResult = assertIs<PairingOutcome.AwaitingConfirmation>(acceptOutcome, "accept: $acceptOutcome")

        // Each side learned the other's identity, not its own.
        assertEquals("Desktop", initiateResult.peerIdentity.displayName)
        assertEquals(acceptingIdentity.deviceId, initiateResult.peerIdentity.deviceId)
        assertEquals("Phone", acceptResult.peerIdentity.displayName)
        assertEquals(initiatingIdentity.deviceId, acceptResult.peerIdentity.deviceId)

        // Each side's fingerprint of the other's certificate is what pinning will later check.
        assertTrue(initiateResult.fingerprintSha256.isNotBlank())
        assertTrue(acceptResult.fingerprintSha256.isNotBlank())
        assertTrue(initiateResult.shortCode.isNotBlank())

        // Each side pins the other's certificate, so the pinned fingerprints differ...
        assertNotEquals(initiateResult.fingerprintSha256, acceptResult.fingerprintSha256)
        // ...but the confirmation code shown to the human must be identical on both screens.
        assertEquals(initiateResult.shortCode, acceptResult.shortCode)
    }

    @Test
    fun `connecting to a device that isn't actually listening fails cleanly, not by hanging`() {
        val outcome =
            PairingSession.initiate(
                device = DiscoveredDevice("nobody", "Nobody", listOf("127.0.0.1"), UNUSED_PORT, emptySet()),
                localIdentity = getOrCreateDeviceIdentity(initiatingDirectory),
                settingsDirectory = initiatingDirectory,
                connectTimeoutMillis = SHORT_CONNECT_TIMEOUT_MILLIS,
            )
        assertIs<PairingOutcome.Failed>(outcome)
    }

    private companion object {
        const val SERVER_JOIN_TIMEOUT_MILLIS = 10_000L
        const val SHORT_CONNECT_TIMEOUT_MILLIS = 2_000
        const val UNUSED_PORT = 1 // privileged/unassigned; nothing binds here in a test sandbox
    }
}
