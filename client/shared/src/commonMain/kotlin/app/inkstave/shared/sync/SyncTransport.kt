package app.inkstave.shared.sync

/**
 * An open, authenticated channel to a paired peer, carrying [CaptureSessionMessage]s
 * (`docs/sync-protocol.md`'s "Capture session"). [send]/[receive] are blocking (this project's
 * only transport, [LanSyncTransport], is a plain TLS socket -- callers run session I/O on a
 * background thread/`Dispatchers.IO`, the same pattern `LibraryScreen.runImport` already uses for
 * other blocking I/O).
 */
interface SyncConnection : AutoCloseable {
    fun send(message: CaptureSessionMessage)

    fun receive(): CaptureSessionMessage
}

/**
 * The transport abstraction `docs/architecture.md`/`docs/decisions/0003-sync-approach.md` calls
 * for: upper-layer sync/session logic depends on this interface, not directly on "a TLS socket,"
 * so a future relay/cloud transport (ADR-0003's explicitly-deferred future work) can be a second
 * implementation of this same interface without the capture-session logic built on top of it
 * changing. `LanSyncTransport` (`jvmCommon`) is the only implementation for v1.
 */
interface SyncTransport {
    /** Opens a connection to [peer] at [host]:[port] (typically from a recent `DiscoveredDevice`), authenticated
     * via [peer]'s pinned certificate fingerprint -- throws if the device actually reachable at that address
     * doesn't present that exact certificate (see `LanSyncTransport.connect`'s doc for why that check exists
     * even though the peer is already in the trust store). */
    fun connect(
        peer: TrustedPeer,
        host: String,
        port: Int,
    ): SyncConnection
}
