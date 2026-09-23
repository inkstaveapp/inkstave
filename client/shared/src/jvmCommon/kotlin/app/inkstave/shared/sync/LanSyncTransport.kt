package app.inkstave.shared.sync

import java.io.File
import java.io.IOException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** [SyncConnection] over a plain TLS socket, both directions using [CaptureSessionWire]'s framing. */
internal class LanSyncConnection(
    private val socket: SSLSocket,
) : SyncConnection {
    override fun send(message: CaptureSessionMessage) = MessageFraming.writeFrame(socket.outputStream, CaptureSessionWire.encode(message))

    override fun receive(): CaptureSessionMessage = CaptureSessionWire.decode(MessageFraming.readFrame(socket.inputStream))

    override fun close() = socket.close()
}

/**
 * The v1 (and, per ADR-0003, currently only) [SyncTransport] implementation: a direct TLS
 * connection on the local network, authenticated by [peer]'s pinned certificate fingerprint
 * ([PinnedFingerprintTrustManager] -- built from [trustStore], so only an already-paired peer's
 * connection can ever succeed).
 */
class LanSyncTransport(
    private val settingsDirectory: File,
    private val trustStore: PeerTrustStore,
) : SyncTransport {
    /**
     * Connects to [peer] at [host]:[port] and additionally verifies the certificate actually
     * presented matches [peer]'s specific pinned fingerprint, not merely *some* trusted peer's --
     * [PinnedFingerprintTrustManager] alone only proves the presented certificate belongs to *a*
     * paired peer; without this extra check, a stale or mistaken [host]/[port] (e.g. a discovery
     * cache pointing at an address a *different* paired peer has since taken over via DHCP) could
     * silently connect to the wrong device rather than failing loudly.
     */
    override fun connect(
        peer: TrustedPeer,
        host: String,
        port: Int,
    ): SyncConnection {
        val sslContext = deviceIdentitySslContext(settingsDirectory, PinnedFingerprintTrustManager(trustStore))
        val socket = sslContext.socketFactory.createSocket(host, port) as SSLSocket
        socket.startHandshake()
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
 * Listens for incoming, already-authenticated sync connections (the desktop/"processing" role
 * accepting a capture session from a paired phone, `docs/sync-protocol.md`) -- TLS's own handshake
 * enforces [PinnedFingerprintTrustManager]'s check before [acceptOne] ever returns, so a
 * connection from an unpaired device never reaches application code at all.
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
            // Without this, the JDK's default server-only TLS auth means the server never
            // actually requests the connecting client's certificate at all -- so
            // PinnedFingerprintTrustManager.checkClientTrusted would never even run, and an
            // *unpaired* device's connection would succeed. This is the real security-critical
            // line in this class; `LanSyncTransportIntegrationTest`'s "unpaired sender" test is
            // what caught it missing.
            needClientAuth = true
        }

    /** The actual port bound (useful when [port] was `0`, "pick any free port" -- what [SyncDiscovery.advertise]
     * should advertise this device as reachable on). */
    val boundPort: Int get() = serverSocket.localPort

    /** Blocks until one already-paired peer connects, then returns the authenticated connection. Callers loop
     * this on a background thread/coroutine while willing to receive a capture session. */
    fun acceptOne(): SyncConnection {
        val socket = serverSocket.accept() as SSLSocket
        socket.startHandshake()
        return LanSyncConnection(socket)
    }

    override fun close() = serverSocket.close()
}
