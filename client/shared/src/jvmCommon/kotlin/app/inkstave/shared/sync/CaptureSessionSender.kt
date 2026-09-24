package app.inkstave.shared.sync

import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Outcome of one [CaptureSessionSender.send] attempt -- a sealed result rather than exceptions
 * alone, matching [PairingOutcome]'s shape, since "the peer isn't currently reachable" is a real,
 * expected, UI-relevant outcome (the paired desktop's app might just not be running right now),
 * not something to report the same way as a genuine transfer failure.
 */
sealed interface CaptureSessionSendOutcome {
    /** Every photo was sent and the connection closed normally. */
    data object Sent : CaptureSessionSendOutcome

    /** [peer] is a paired, trusted device, but nothing advertising its [TrustedPeer.deviceId] was found on the
     * local network within the discovery timeout -- most commonly because the peer's app isn't currently
     * running, or it's on a different network right now. Not a pairing/trust failure: the peer is still
     * trusted, it just couldn't be located to connect to. */
    data class PeerNotFound(
        val peer: TrustedPeer,
    ) : CaptureSessionSendOutcome

    /** The peer was found, but connecting or sending failed (e.g. the certificate it's currently presenting no
     * longer matches the pinned fingerprint -- see [LanSyncTransport.connect] -- or the connection dropped
     * mid-transfer). */
    data class Failed(
        val reason: String,
    ) : CaptureSessionSendOutcome
}

/**
 * Sends one capture session (`docs/sync-protocol.md`'s "Capture session") to an already-paired
 * [TrustedPeer]: pairing only ever pinned that peer's certificate fingerprint, never a live
 * address (a device's IP changes, e.g. via DHCP), so every real send first finds where the peer
 * currently is via [discovery] -- the same reason [LanSyncTransport.connect] re-checks the
 * fingerprint at connect time rather than trusting a stale cached address, just one layer up.
 *
 * This class only composes [SyncDiscovery]/[SyncTransport]/[CaptureSessionMessage] -- it
 * introduces no new networking or cryptographic primitive of its own.
 */
class CaptureSessionSender(
    private val discovery: SyncDiscovery,
    private val transport: SyncTransport,
) {
    /**
     * Finds [peer] on the local network (up to [discoveryTimeoutMillis]) and, if found, sends
     * [photos] as one session: [CaptureSessionMessage.SessionStart], then one
     * [CaptureSessionMessage.Photo] per photo in [photos]' given order (that order becomes the
     * score's page order on the receiving side, the same M1 constraint local image import already
     * has -- see [CaptureSessionReceiver]), then [CaptureSessionMessage.SessionEnd].
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
     * Browses [discovery] until a device advertising [deviceId] appears, or [timeoutMillis] elapses -- a
     * short-lived listener scoped to this one lookup, unlike [PairingScreen]'s standing browse for as long as
     * that screen is on-screen: sending a capture session is a one-shot action, not a UI state to keep live.
     * [SyncDiscovery] has no "stop browsing" call of its own; this class doesn't own [discovery]'s lifecycle
     * (the caller does, typically closing it right after this call returns, the same short-lived-instance
     * pattern [MainActivity]'s per-send wiring uses).
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
