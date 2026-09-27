package app.inkstave.shared.sync

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * The result of a pairing attempt. [AwaitingConfirmation] does **not** mean trusted: the caller must
 * show [AwaitingConfirmation.shortCode] and get explicit human confirmation before storing
 * [AwaitingConfirmation.fingerprintSha256]. Never auto-trust on a successful handshake; it proves
 * nothing about identity.
 *
 * `fingerprintSha256` is the peer's certificate (what gets pinned); `shortCode` combines both
 * certificates so both screens show the same code.
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
 * The pairing exchange: a TLS handshake with [TrustAnyPeerCertificate] (its only legitimate use),
 * then each side sends its [DeviceIdentity] in one frame to learn the display name. Fingerprints
 * come from the handshake certificates, never from the identity messages.
 *
 * To avoid both sides blocking on a read, the initiator writes first and the acceptor reads first.
 */
object PairingSession {
    /** Connects to [device], trying each of its addresses, and runs the exchange as the initiator. */
    fun initiate(
        device: DiscoveredDevice,
        localIdentity: DeviceIdentity,
        settingsDirectory: File,
        connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): PairingOutcome =
        try {
            val sslContext = deviceIdentitySslContext(settingsDirectory, TrustAnyPeerCertificate)
            val connected =
                firstReachable(device.hosts) { host ->
                    val attempt = sslContext.socketFactory.createSocket() as SSLSocket
                    try {
                        attempt.connect(InetSocketAddress(host, device.port), connectTimeoutMillis)
                        attempt
                    } catch (e: IOException) {
                        attempt.close()
                        throw e
                    }
                }
            connected.use { socket ->
                rethrowingSecurityExceptionsAsIO { socket.startHandshake() }
                writeIdentity(socket, localIdentity)
                val peerIdentity = readIdentity(socket)
                outcomeFor(socket, peerIdentity)
            }
        } catch (e: IOException) {
            PairingOutcome.Failed(e.message ?: "connection failed")
        }

    /** The acceptor's half of the exchange, on a connected but not yet handshaken [socket]. */
    internal fun accept(
        socket: SSLSocket,
        localIdentity: DeviceIdentity,
    ): PairingOutcome =
        try {
            socket.use {
                rethrowingSecurityExceptionsAsIO { socket.startHandshake() }
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
        // Both sides present an identity certificate in this handshake, so this is never null.
        val localCertificate =
            checkNotNull(socket.session.localCertificates?.firstOrNull()) { "pairing session presented no local certificate" }
        return PairingOutcome.AwaitingConfirmation(
            peerIdentity = peerIdentity,
            // Must stay the peer's own certificate: it's what later connections are checked against.
            fingerprintSha256 = CertificateFingerprint.sha256(peerCertificate),
            // Computed identically on both devices, so the two screens can be compared.
            shortCode = CertificateFingerprint.combinedShortCode(localCertificate, peerCertificate),
        )
    }

    private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
}

/**
 * Accepts pairing attempts one at a time, only while the user has pairing mode open; it must not run
 * as an always-on background listener. Trusts any certificate for the handshake, like
 * [PairingSession.initiate].
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
            // Required: without it the client presents no certificate and there's nothing to
            // fingerprint.
            needClientAuth = true
        }

    /** The port actually bound (when [port] is 0). */
    val boundPort: Int get() = serverSocket.localPort

    /** Blocks until one device completes the exchange and returns its outcome; call in a loop off the main thread. */
    fun acceptOne(localIdentity: DeviceIdentity): PairingOutcome = PairingSession.accept(serverSocket.accept() as SSLSocket, localIdentity)

    override fun close() = serverSocket.close()
}
