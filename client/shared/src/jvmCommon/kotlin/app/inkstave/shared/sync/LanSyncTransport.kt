package app.inkstave.shared.sync

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Runs a TLS handshake step and rethrows [GeneralSecurityException] as [IOException]. For TLS 1.3
 * client-certificate rejection the JDK lets the trust manager's `CertificateException` escape
 * `startHandshake()` unwrapped, and callers only catch [IOException]: without this, one unpaired
 * device would kill the sync listener loop instead of failing that one connection.
 */
internal inline fun <T> rethrowingSecurityExceptionsAsIO(block: () -> T): T =
    try {
        block()
    } catch (e: GeneralSecurityException) {
        throw IOException("TLS handshake failed: ${e.message}", e)
    }

/** [SyncConnection] over a plain TLS socket, both directions using [CaptureSessionWire]'s framing. */
internal class LanSyncConnection(
    private val socket: SSLSocket,
) : SyncConnection {
    override fun send(message: CaptureSessionMessage) = MessageFraming.writeFrame(socket.outputStream, CaptureSessionWire.encode(message))

    override fun receive(): CaptureSessionMessage = CaptureSessionWire.decode(MessageFraming.readFrame(socket.inputStream))

    override fun close() = socket.close()
}

/** [SyncTransport] over a direct LAN TLS connection; only peers in [trustStore] can connect. */
class LanSyncTransport(
    private val settingsDirectory: File,
    private val trustStore: PeerTrustStore,
) : SyncTransport {
    /**
     * Connects to [peer] and also checks the certificate matches [peer]'s own fingerprint, not just
     * any trusted peer's; otherwise a stale address now used by another paired device would
     * silently connect to the wrong device.
     */
    override fun connect(
        peer: TrustedPeer,
        host: String,
        port: Int,
    ): SyncConnection {
        val sslContext = deviceIdentitySslContext(settingsDirectory, PinnedFingerprintTrustManager(trustStore))
        val socket = sslContext.socketFactory.createSocket(host, port) as SSLSocket
        rethrowingSecurityExceptionsAsIO { socket.startHandshake() }
        val actualFingerprint = CertificateFingerprint.sha256(socket.session.peerCertificates.first() as X509Certificate)
        if (actualFingerprint != peer.certificateFingerprintSha256) {
            socket.close()
            throw IOException(
                "connected to $host:$port, but its certificate does not match the pinned fingerprint for " +
                    "peer '${peer.displayName}' -- refusing to treat this as that peer",
            )
        }
        return LanSyncConnection(socket)
    }
}

/**
 * Accepts sync connections from paired devices. The TLS handshake enforces pinning before [acceptOne]
 * returns, so an unpaired device never reaches application code.
 */
class SyncServer(
    settingsDirectory: File,
    trustStore: PeerTrustStore,
    port: Int = 0,
) : AutoCloseable {
    private val serverSocket =
        (
            deviceIdentitySslContext(settingsDirectory, PinnedFingerprintTrustManager(trustStore))
                .serverSocketFactory
                .createServerSocket(port) as SSLServerSocket
        ).apply {
            // Security-critical: without it the server never requests a client certificate, the
            // pinning check never runs, and unpaired devices could connect.
            needClientAuth = true
        }

    /** The port actually bound (when [port] is 0); the one to advertise. */
    val boundPort: Int get() = serverSocket.localPort

    /**
     * Blocks until a paired peer connects and returns the authenticated connection. A rejected
     * handshake always surfaces as [IOException], so a listener loop can skip it and keep going.
     */
    fun acceptOne(): SyncConnection {
        val socket = serverSocket.accept() as SSLSocket
        rethrowingSecurityExceptionsAsIO { socket.startHandshake() }
        return LanSyncConnection(socket)
    }

    override fun close() = serverSocket.close()
}
