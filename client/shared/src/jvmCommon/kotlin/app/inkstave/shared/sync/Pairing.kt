package app.inkstave.shared.sync

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * The result of a pairing attempt (`docs/sync-protocol.md`'s "Pairing & trust"). Reaching
 * [AwaitingConfirmation] means the TLS handshake and identity exchange both succeeded -- it does
 * **not** mean the peer is trusted yet. A caller must show [AwaitingConfirmation.shortCode] to
 * the human and get an explicit confirmation before calling `PeerTrustStore.add` with
 * [AwaitingConfirmation.fingerprintSha256]; skipping that step and auto-trusting on a successful
 * handshake alone would defeat the entire point of pairing (see `TrustAnyPeerCertificate`'s doc
 * for why the handshake succeeding proves nothing about identity on its own).
 */
sealed interface PairingOutcome {
    data class AwaitingConfirmation(
        val peerIdentity: DeviceIdentity,
        val fingerprintSha256: String,
        val shortCode: String,
    ) : PairingOutcome

    data class Failed(
        val reason: String,
    ) : PairingOutcome
}

/**
 * The pairing exchange itself: a TLS handshake (trusting any certificate -- this is the one
 * legitimate use of [TrustAnyPeerCertificate], see its doc) followed by both sides exchanging
 * their [DeviceIdentity] as one framed JSON message each ([MessageFraming]). The certificate
 * exchanged as part of the handshake itself is what [PairingOutcome.AwaitingConfirmation]'s
 * fingerprint is computed from -- the identity exchange is a separate, additional step purely to
 * learn the peer's human-readable [DeviceIdentity.displayName], since a certificate alone doesn't
 * carry that (only [DeviceIdentity.deviceId], which is this identity's certificate subject CN --
 * see `DeviceIdentityProvisioning.kt`'s actuals).
 *
 * Ordering is fixed to avoid both sides blocking on a read simultaneously: whichever side called
 * [initiate] (the connecting side) always writes its identity first, then reads; whichever side
 * is inside [PairingServer.acceptOne] (the accepting side) always reads first, then writes.
 */
object PairingSession {
    /**
     * Connects to [device] (typically a `SyncDiscovery.browse` result) and runs the pairing
     * exchange as the initiating side.
     */
    fun initiate(
        device: DiscoveredDevice,
        localIdentity: DeviceIdentity,
        settingsDirectory: File,
        connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): PairingOutcome =
        try {
            val sslContext = deviceIdentitySslContext(settingsDirectory, TrustAnyPeerCertificate)
            (sslContext.socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.connect(InetSocketAddress(device.host, device.port), connectTimeoutMillis)
                socket.startHandshake()
                writeIdentity(socket, localIdentity)
                val peerIdentity = readIdentity(socket)
                outcomeFor(socket, peerIdentity)
            }
        } catch (e: IOException) {
            PairingOutcome.Failed(e.message ?: "connection failed")
        }

    /** The accepting side's half of the same exchange, given an already-connected, not-yet-handshaken [socket]
     * (from [PairingServer.acceptOne]). */
    internal fun accept(
        socket: SSLSocket,
        localIdentity: DeviceIdentity,
    ): PairingOutcome =
        try {
            socket.use {
                socket.startHandshake()
                val peerIdentity = readIdentity(socket)
                writeIdentity(socket, localIdentity)
                outcomeFor(socket, peerIdentity)
            }
        } catch (e: IOException) {
            PairingOutcome.Failed(e.message ?: "connection failed")
        }

    private fun writeIdentity(
        socket: SSLSocket,
        identity: DeviceIdentity,
    ) {
        val bytes = Json.encodeToString(DeviceIdentity.serializer(), identity).encodeToByteArray()
        MessageFraming.writeFrame(socket.outputStream, bytes)
    }

    private fun readIdentity(socket: SSLSocket): DeviceIdentity {
        val bytes = MessageFraming.readFrame(socket.inputStream)
        return Json.decodeFromString(DeviceIdentity.serializer(), bytes.decodeToString())
    }

    private fun outcomeFor(
        socket: SSLSocket,
        peerIdentity: DeviceIdentity,
    ): PairingOutcome {
        val peerCertificate = socket.session.peerCertificates.first() as X509Certificate
        return PairingOutcome.AwaitingConfirmation(
            peerIdentity = peerIdentity,
            fingerprintSha256 = CertificateFingerprint.sha256(peerCertificate),
            shortCode = CertificateFingerprint.shortCode(peerCertificate),
        )
    }

    private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
}

/**
 * Listens for one incoming pairing attempt at a time (`docs/sync-protocol.md`: pairing is a
 * deliberate, user-initiated action -- "start pairing mode" on the accepting device, not a
 * background listener always open to any connection). Trusts any certificate for the handshake
 * itself, the same [TrustAnyPeerCertificate] TOFU pattern [PairingSession.initiate] uses -- see
 * that object's doc.
 */
class PairingServer(
    settingsDirectory: File,
    port: Int = 0,
) : AutoCloseable {
    private val serverSocket =
        (
            deviceIdentitySslContext(settingsDirectory, TrustAnyPeerCertificate)
                .serverSocketFactory
                .createServerSocket(port) as SSLServerSocket
        ).apply {
            // A JDK SSLServerSocket does not request a client certificate by default (plain
            // server-only TLS auth is the common case it's built for) -- without this, the
            // pairing exchange's own identity check would silently run against no certificate at
            // all rather than the peer's real one, which `outcomeFor`'s
            // `socket.session.peerCertificates` call would then fail on with
            // SSLPeerUnverifiedException ("peer not authenticated"), not the fingerprint
            // comparison the rest of this protocol depends on. `PairingSessionIntegrationTest` is
            // what caught this missing setting.
            needClientAuth = true
        }

    /** The actual port bound (useful when [port] was `0`, "pick any free port"). */
    val boundPort: Int get() = serverSocket.localPort

    /** Blocks until one device connects and completes the pairing exchange, then returns its outcome. Callers
     * loop this (in a background coroutine/thread) while pairing mode is active in the UI. */
    fun acceptOne(localIdentity: DeviceIdentity): PairingOutcome = PairingSession.accept(serverSocket.accept() as SSLSocket, localIdentity)

    override fun close() = serverSocket.close()
}
