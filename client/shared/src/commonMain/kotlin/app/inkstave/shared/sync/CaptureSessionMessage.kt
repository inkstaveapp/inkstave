package app.inkstave.shared.sync

/**
 * Messages of one capture session over a [SyncConnection] (`docs/sync-protocol.md`): [SessionStart],
 * one [Photo] per page as it is taken (not batched, so the receiver can show progress), then
 * [SessionEnd]. `sessionId` ties a session's messages together; it is part of the protocol contract
 * even though each connection currently carries a single session.
 *
 * Serialization lives in [CaptureSessionWire].
 */
sealed interface CaptureSessionMessage {
    data class SessionStart(
        val sessionId: String,
        val scoreTitle: String,
    ) : CaptureSessionMessage

    /** One captured page. [sequenceIndex] is its 0-based page order within the session, which need not
     * match arrival order on every transport (TLS over TCP does preserve it). */
    class Photo(
        val sessionId: String,
        val sequenceIndex: Int,
        val bytes: ByteArray,
    ) : CaptureSessionMessage {
        // Not a data class: that would compare `bytes` by reference instead of by content.
        override fun equals(other: Any?): Boolean =
            other is Photo && sessionId == other.sessionId && sequenceIndex == other.sequenceIndex && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = sessionId.hashCode() * 31 * 31 + sequenceIndex.hashCode() * 31 + bytes.contentHashCode()
    }

    data class SessionEnd(
        val sessionId: String,
    ) : CaptureSessionMessage
}
