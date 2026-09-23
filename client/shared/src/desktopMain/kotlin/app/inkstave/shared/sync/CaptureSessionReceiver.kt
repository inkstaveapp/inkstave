package app.inkstave.shared.sync

import app.inkstave.shared.format.Manifest
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.importer.ProcessedPage
import app.inkstave.shared.processing.ProcessingServiceClient
import app.inkstave.shared.processing.ProcessingServiceException

/**
 * Whether a received capture session ended up run through `processing-service`'s cleanup/OCR
 * pipeline, or imported as-is -- [CaptureSessionReceiver.receiveAndImport]'s caller (`Main.kt`'s
 * listener loop) logs this so "why does this score look unprocessed" has an answer visible
 * somewhere, rather than the two outcomes being silently indistinguishable.
 */
sealed interface CaptureSessionImportOutcome {
    val manifest: Manifest

    /** Every page was successfully cleaned/OCR'd by `processing-service` before being written. */
    data class Processed(
        override val manifest: Manifest,
    ) : CaptureSessionImportOutcome

    /** Imported with the original, unprocessed photo bytes -- either no [ProcessingServiceClient]
     * was configured at all, or it was configured but unreachable/failed partway through (see
     * [reason]). Never a partial mix of processed and raw pages within one score -- see
     * [CaptureSessionReceiver.receiveAndImport]'s doc for why. */
    data class ImportedRaw(
        override val manifest: Manifest,
        val reason: String,
    ) : CaptureSessionImportOutcome
}

/**
 * The receiving side of one capture session (`docs/sync-protocol.md`), given a [SyncConnection]
 * [SyncServer] has already accepted and authenticated (an unpaired sender's connection never
 * reaches this class at all -- TLS itself rejects it first, see [SyncServer]'s doc).
 *
 * Reads [CaptureSessionMessage.SessionStart], then [CaptureSessionMessage.Photo] messages until
 * [CaptureSessionMessage.SessionEnd], then -- since M4's follow-up pipeline-integration slice --
 * runs each photo through [processingClient] (if one is given and reachable) before writing the
 * result, via [LibraryImporter.importProcessedPages]. If [processingClient] is `null`, or
 * unreachable, or any single photo in the session fails to process, the *entire* session falls
 * back to [LibraryImporter.importImages] with the original, unprocessed bytes -- deliberately not
 * a partial per-photo fallback (some pages processed, some not, within the same score): a score
 * where every page went through the same path is simpler to reason about, both for this code and
 * for a user looking at their library, than one with silently mixed provenance per page. The one
 * cost is that a mid-session failure (e.g. the service crashes after successfully processing 2 of
 * 5 photos) discards those 2 results and reprocesses nothing -- an acceptable trade for a real,
 * expected failure mode (`docs/architecture.md`: the service is a separate process that can be
 * down, crash, or not be installed at all) rather than a bug to avoid.
 */
object CaptureSessionReceiver {
    /**
     * Reads one full session from [connection] and imports it via [importer], returning which
     * outcome happened. Throws (`IllegalStateException`, or whatever [SyncConnection.receive]
     * itself throws on a dropped/closed connection) if the session ends before a
     * [CaptureSessionMessage.SessionEnd] arrives -- silently importing a partial batch (pages
     * missing, or corrupted mid-transfer) would be worse than a clear failure the caller can log,
     * since a user re-sending the whole session is a normal, cheap recovery from that.
     *
     * @param processingClient `null` means "don't even try" (e.g. this desktop has never located a
     * `processing-service` checkout, [app.inkstave.shared.processing.ProcessingServiceLauncher]
     * never got it running) -- every session then always imports raw, without a per-call health
     * check that would only ever fail the same way.
     */
    fun receiveAndImport(
        connection: SyncConnection,
        importer: LibraryImporter,
        processingClient: ProcessingServiceClient?,
    ): CaptureSessionImportOutcome {
        val start =
            connection.receive() as? CaptureSessionMessage.SessionStart
                ?: error("expected SessionStart as the first message of a capture session")

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
        val photos = photosBySequence.values.toList()

        val processed = processingClient?.let { tryProcessAll(it, start.sessionId, photos) }
        return if (processed != null) {
            CaptureSessionImportOutcome.Processed(importer.importProcessedPages(title = start.scoreTitle, processedPages = processed))
        } else {
            val reason =
                when {
                    processingClient == null -> "no processing-service configured for this device"
                    else -> "processing-service unreachable, or a photo in this session failed to process"
                }
            CaptureSessionImportOutcome.ImportedRaw(
                importer.importImages(title = start.scoreTitle, imageFiles = photos),
                reason = reason,
            )
        }
    }

    /** `null` -- not a thrown exception -- signals "fall back to raw import," per this object's own doc on why a
     * failure anywhere in the batch abandons the whole processed attempt rather than mixing processed and raw
     * pages in one score. */
    private fun tryProcessAll(
        client: ProcessingServiceClient,
        sessionId: String,
        photos: List<ByteArray>,
    ): List<ProcessedPage>? {
        if (!client.isHealthy()) return null
        return try {
            photos.mapIndexed { index, bytes ->
                val response = client.processPage(bytes, sessionId, index)
                ProcessedPage(
                    pngBytes = response.decodedImageBytes(),
                    width = response.width,
                    height = response.height,
                    aspectRatioClass = response.aspectRatioClass,
                    processing = response.processing,
                    ocr = response.ocr,
                )
            }
        } catch (e: ProcessingServiceException) {
            System.err.println("inkstave: capture-session processing failed, falling back to raw import: ${e.message}")
            null
        }
    }
}
