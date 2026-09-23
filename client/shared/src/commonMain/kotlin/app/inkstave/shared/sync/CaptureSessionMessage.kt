package app.inkstave.shared.sync

/**
 * Messages sent over an authenticated [SyncConnection] during a capture session
 * (`docs/sync-protocol.md`'s "Capture session"): [SessionStart] once when the mobile app begins
 * photographing a score, one [Photo] per captured page as it's taken (not batched -- so the
 * receiving side can show live progress and could start processing photos as they arrive, per
 * that doc), and [SessionEnd] once the batch is finished. [sessionId] ties all of a session's
 * messages together (a random ID the sender generates at [SessionStart] time) -- not currently
 * used to multiplex more than one connection's messages (each [SyncConnection] carries exactly
 * one capture session for now), but included from the start since `docs/sync-protocol.md`
 * specifies it as part of the contract ("sequence metadata (session ID, capture index,
 * timestamp)") for whatever multi-session bookkeeping a later slice (M6's broader library sync)
 * needs.
 *
 * Wire encoding is [CaptureSessionWire] (`jvmCommon`) -- this sealed interface only describes the
 * message shapes, not how they're serialized, matching the `SyncTransport` abstraction's own
 * split between contract and implementation.
 */
sealed interface CaptureSessionMessage {
    data class SessionStart(
        val sessionId: String,
        val scoreTitle: String,
    ) : CaptureSessionMessage

    /** One captured page. [sequenceIndex] is the page's 0-based order within the session -- not
     * necessarily the order [Photo] messages arrive on an unreliable transport, though TLS-over-TCP
     * (this project's only transport for v1) does preserve send order. */
    class Photo(
        val sessionId: String,
        val sequenceIndex: Int,
        val bytes: ByteArray,
    ) : CaptureSessionMessage {
        // A plain `data class` would compare `bytes` by reference (the default ByteArray
        // equals/hashCode), which is almost never what a caller comparing two decoded Photo
        // messages actually wants -- content equality, matching how CaptureSessionWireTest
        // asserts a round-tripped Photo equals the original.
        override fun equals(other: Any?): Boolean =
            other is Photo && sessionId == other.sessionId && sequenceIndex == other.sequenceIndex && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = sessionId.hashCode() * 31 * 31 + sequenceIndex.hashCode() * 31 + bytes.contentHashCode()
    }

    data class SessionEnd(
        val sessionId: String,
    ) : CaptureSessionMessage
}
