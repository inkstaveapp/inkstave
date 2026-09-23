package app.inkstave.shared.sync

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Runs [block] (a TLS handshake step that can trigger [PinnedFingerprintTrustManager]) and
 * rethrows any [GeneralSecurityException] as an [IOException] -- **a real bug found on real
 * hardware, not a defensive nicety**: `X509TrustManager.checkClientTrusted`/`checkServerTrusted`
 * are declared to throw `CertificateException` (a [GeneralSecurityException], *not* an
 * [IOException]), and for TLS 1.3 client-certificate rejection specifically, the JDK's own SSL
 * engine does not wrap that exception before it escapes `startHandshake()` -- confirmed directly
 * via `-Djavax.net.debug=ssl`, not assumed: the real stack trace shows
 * [PinnedFingerprintTrustManager.checkClientTrusted]'s `CertificateException` propagating straight
 * out of `SSLSocketImpl.startHandshake()`. Every caller here, including the real production
 * capture-session listener (`Main.kt`'s `startSyncListener`), only ever catches [IOException]
 * around a `startHandshake()` call -- without this, a single unpaired device's connection attempt
 * would throw an uncaught, unexpected-type exception straight through that listener's `while(true)`
 * loop, silently killing sync for the rest of the app's run, not just failing that one connection
 * the way the loop's own comment already says it's designed to.
 *
 * `internal`, not `private`: [PairingSession]'s handshake calls use [TrustAnyPeerCertificate],
 * which never throws today, so they're not *currently* exposed to this -- but they're the same
 * shape of call, and wrapping them the same way is cheap insurance against this exact bug coming
 * back the moment that trust manager's behavior ever changes.
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

    /**
     * Blocks until one already-paired peer connects, then returns the authenticated connection.
     * Callers loop this on a background thread/coroutine while willing to receive a capture
     * session. Always throws [IOException] (never a raw [GeneralSecurityException] -- see
     * [rethrowingSecurityExceptionsAsIO]) for a rejected handshake, so callers that (correctly)
     * only catch [IOException] around this call don't get an uncaught exception instead of the
     * "one bad connection, keep listening" behavior they intend.
     */
    fun acceptOne(): SyncConnection {
        val socket = serverSocket.accept() as SSLSocket
        rethrowingSecurityExceptionsAsIO { socket.startHandshake() }
        return LanSyncConnection(socket)
    }

    override fun close() = serverSocket.close()
}
