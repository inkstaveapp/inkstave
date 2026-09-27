package app.inkstave.shared.sync

import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Outcome of one [CaptureSessionSender.send]; "peer not running" is an expected result, not an exception. */
sealed interface CaptureSessionSendOutcome {
    /** Every photo was sent and the connection closed normally. */
    data object Sent : CaptureSessionSendOutcome

    /** [peer] wasn't found on the local network in time (app not running, or another network). The peer
     * is still trusted; this is not a pairing failure. */
    data class PeerNotFound(
        val peer: TrustedPeer,
    ) : CaptureSessionSendOutcome

    /** The peer was found but connecting or sending failed, e.g. its certificate no longer matches the
     * pinned fingerprint or the connection dropped. */
    data class Failed(
        val reason: String,
    ) : CaptureSessionSendOutcome
}

/**
 * Sends one capture session to a paired [TrustedPeer]. Pairing pins a certificate, not an address
 * (addresses change), so every send first locates the peer via [discovery].
 */
class CaptureSessionSender(
    private val discovery: SyncDiscovery,
    private val transport: SyncTransport,
) {
    /**
     * Finds [peer] (within [discoveryTimeoutMillis]) and sends [photos] as one session. The order of
     * [photos] becomes the page order of the received score.
     */
    fun send(
        peer: TrustedPeer,
        scoreTitle: String,
        photos: List<ByteArray>,
        discoveryTimeoutMillis: Long = DEFAULT_DISCOVERY_TIMEOUT_MILLIS,
    ): CaptureSessionSendOutcome {
        val device = discoverDeviceById(peer.deviceId, discoveryTimeoutMillis) ?: return CaptureSessionSendOutcome.PeerNotFound(peer)
        return try {
            firstReachable(device.hosts) { host -> transport.connect(peer, host, device.port) }.use { connection ->
                val sessionId = UUID.randomUUID().toString()
                connection.send(CaptureSessionMessage.SessionStart(sessionId, scoreTitle))
                photos.forEachIndexed { index, bytes -> connection.send(CaptureSessionMessage.Photo(sessionId, index, bytes)) }
                connection.send(CaptureSessionMessage.SessionEnd(sessionId))
            }
            CaptureSessionSendOutcome.Sent
        } catch (e: IOException) {
            CaptureSessionSendOutcome.Failed(e.message ?: "connection failed")
        }
    }

    /**
     * Browses until a device advertising [deviceId] appears or [timeoutMillis] elapses. [SyncDiscovery]
     * can't stop browsing, so the caller owns [discovery] and closes it afterwards.
     */
    private fun discoverDeviceById(
        deviceId: String,
        timeoutMillis: Long,
    ): DiscoveredDevice? {
        val found = CompletableFuture<DiscoveredDevice>()
        discovery.browse(
            object : SyncDeviceListener {
                override fun onDeviceFound(device: DiscoveredDevice) {
                    if (device.deviceId == deviceId) found.complete(device)
                }

                override fun onDeviceLost(deviceId: String) = Unit
            },
        )
        return try {
            found.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            null
        }
    }

    private companion object {
        const val DEFAULT_DISCOVERY_TIMEOUT_MILLIS = 8_000L
    }
}
