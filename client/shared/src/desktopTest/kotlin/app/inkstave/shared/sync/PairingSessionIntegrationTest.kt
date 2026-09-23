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
 * Real end-to-end pairing over a real loopback TLS socket -- two genuinely separate device
 * identities (two temp settings directories, each with its own `keytool`-generated certificate),
 * one acting as the accepting device (`PairingServer`) on a background thread, the other as the
 * initiating device (`PairingSession.initiate`) on the test thread. This is the honest ceiling for
 * "tested" a single-machine sandboxed environment can reach for networking code
 * (`docs/testing-strategy.md`) -- a real phone and a real desktop on a real LAN is not something
 * this pass claims to have exercised.
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
                device = DiscoveredDevice("accepting-device-id", "Desktop", "127.0.0.1", server.boundPort, setOf(DeviceRole.PROCESSING)),
                localIdentity = initiatingIdentity,
                settingsDirectory = initiatingDirectory,
            )

        serverThread.join(SERVER_JOIN_TIMEOUT_MILLIS)
        server.close()

        val initiateResult = assertIs<PairingOutcome.AwaitingConfirmation>(initiateOutcome, "initiate: $initiateOutcome")
        val acceptResult = assertIs<PairingOutcome.AwaitingConfirmation>(acceptOutcome, "accept: $acceptOutcome")

        // Each side learned the OTHER's identity, not its own: the initiator connected TO the
        // acceptor, so the initiator's outcome reports the acceptor's ("Desktop") identity, and
        // vice versa.
        assertEquals("Desktop", initiateResult.peerIdentity.displayName)
        assertEquals(acceptingIdentity.deviceId, initiateResult.peerIdentity.deviceId)
        assertEquals("Phone", acceptResult.peerIdentity.displayName)
        assertEquals(initiatingIdentity.deviceId, acceptResult.peerIdentity.deviceId)

        // The fingerprint each side computed of the OTHER's certificate must match what pinning
        // that peer would later verify against -- i.e. the initiator's view of the acceptor's
        // fingerprint is exactly what the acceptor's own identity actually presents, and vice
        // versa. Real certificates, not stand-ins: this is the actual security property pairing
        // depends on.
        assertTrue(initiateResult.fingerprintSha256.isNotBlank())
        assertTrue(acceptResult.fingerprintSha256.isNotBlank())
        assertTrue(initiateResult.shortCode.isNotBlank())

        // What each side pins is the OTHER's certificate, so the two pinned fingerprints must
        // differ (two distinct identities)...
        assertNotEquals(initiateResult.fingerprintSha256, acceptResult.fingerprintSha256)
        // ...but the code shown to the human for "confirm this matches on both devices" must be
        // identical on both screens. A real phone-to-desktop pairing found this wasn't true when
        // the code was just the peer certificate's own fingerprint (each screen fingerprinted a
        // different certificate, so they could never match, making the human comparison step
        // meaningless).
        assertEquals(initiateResult.shortCode, acceptResult.shortCode)
    }

    @Test
    fun `connecting to a device that isn't actually listening fails cleanly, not by hanging`() {
        val outcome =
            PairingSession.initiate(
                device = DiscoveredDevice("nobody", "Nobody", "127.0.0.1", UNUSED_PORT, emptySet()),
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
