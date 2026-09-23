package app.inkstave.shared.sync

import app.inkstave.shared.format.Manifest
import app.inkstave.shared.importer.LibraryImporter

/**
 * The receiving side of one capture session (`docs/sync-protocol.md`), given a [SyncConnection]
 * [SyncServer] has already accepted and authenticated (an unpaired sender's connection never
 * reaches this class at all -- TLS itself rejects it first, see [SyncServer]'s doc).
 *
 * Reads [CaptureSessionMessage.SessionStart], then [CaptureSessionMessage.Photo] messages until
 * [CaptureSessionMessage.SessionEnd], then imports the finished batch via [LibraryImporter] --
 * **the exact same [LibraryImporter.importImages] path M1's local image import and M4's local
 * camera-capture import already use.** A session received over sync is deliberately treated
 * exactly like a locally-imported image batch for now: running received photos through
 * `processing-service`'s cleanup/OCR pipeline first, and eventually syncing the processed result
 * back to the originating device, are real, separate follow-up work (`ROADMAP.md`), not attempted
 * here -- this class's only job is getting the bytes from the wire into the library correctly.
 */
object CaptureSessionReceiver {
    /**
     * Reads one full session from [connection] and imports it via [importer], returning the
     * resulting [Manifest]. Throws (`IllegalStateException`, or whatever [SyncConnection.receive]
     * itself throws on a dropped/closed connection) if the session ends before a
     * [CaptureSessionMessage.SessionEnd] arrives -- silently importing a partial batch (pages
     * missing, or corrupted mid-transfer) would be worse than a clear failure the caller can
     * log, since a user re-sending the whole session is a normal, cheap recovery from that.
     */
    fun receiveAndImport(
        connection: SyncConnection,
        importer: LibraryImporter,
    ): Manifest {
        val start =
            connection.receive() as? CaptureSessionMessage.SessionStart
                ?: error("expected SessionStart as the first message of a capture session")

        // Sorted, not appended in arrival order: CaptureSessionMessage.Photo.sequenceIndex is the
        // authoritative page order (its own doc notes arrival order isn't guaranteed on every
        // conceivable transport, even though this project's one transport, TLS-over-TCP,
        // happens to preserve it) -- reordering by the index the sender already provided is cheap
        // and removes any dependence on that transport detail here.
        val photosBySequence = sortedMapOf<Int, ByteArray>()
        while (true) {
            when (val message = connection.receive()) {
                is CaptureSessionMessage.Photo -> {
                    check(message.sessionId == start.sessionId) { "received a photo for a different session" }
                    photosBySequence[message.sequenceIndex] = message.bytes
                }
                is CaptureSessionMessage.SessionEnd -> {
                    check(message.sessionId == start.sessionId) { "received a session-end for a different session" }
                    break
                }
                is CaptureSessionMessage.SessionStart -> error("received a second SessionStart mid-session")
            }
        }

        return importer.importImages(title = start.scoreTitle, imageFiles = photosBySequence.values.toList())
    }
}
