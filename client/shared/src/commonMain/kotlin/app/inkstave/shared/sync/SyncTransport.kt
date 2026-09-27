package app.inkstave.shared.sync

/**
 * An open, authenticated channel to a paired peer carrying [CaptureSessionMessage]s. [send] and
 * [receive] block, so callers run them off the main thread.
 */
interface SyncConnection : AutoCloseable {
    fun send(message: CaptureSessionMessage)

    fun receive(): CaptureSessionMessage
}

/**
 * The transport abstraction from ADR-0003: session logic depends on this, not on a TLS socket, so a
 * future relay/cloud transport can be added as another implementation. `LanSyncTransport` is the
 * only one today.
 */
interface SyncTransport {
    /** Opens a connection to [peer] at [host]:[port], authenticated by [peer]'s pinned certificate
     * fingerprint. Throws if the device at that address does not present exactly that certificate. */
    fun connect(
        peer: TrustedPeer,
        host: String,
        port: Int,
    ): SyncConnection
}
