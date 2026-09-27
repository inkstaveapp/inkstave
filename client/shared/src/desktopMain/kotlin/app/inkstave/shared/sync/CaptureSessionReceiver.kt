package app.inkstave.shared.sync

import app.inkstave.shared.format.Manifest
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.importer.ProcessedPage
import app.inkstave.shared.processing.ProcessingServiceClient
import app.inkstave.shared.processing.ProcessingServiceException

/** Whether a received session went through `processing-service` or was imported as-is; callers log it. */
sealed interface CaptureSessionImportOutcome {
    val manifest: Manifest

    /** Every page was successfully cleaned/OCR'd by `processing-service` before being written. */
    data class Processed(
        override val manifest: Manifest,
    ) : CaptureSessionImportOutcome

    /** Imported with the original photo bytes because processing was unavailable or failed ([reason]).
     * Never a mix of processed and raw pages within one score. */
    data class ImportedRaw(
        override val manifest: Manifest,
        val reason: String,
    ) : CaptureSessionImportOutcome
}

/**
 * The receiving side of one capture session, on a connection [SyncServer] has already authenticated
 * (TLS rejects unpaired senders before this runs).
 *
 * Each photo goes through the processing service when it is available. If it isn't, or any photo
 * fails, the whole session is imported raw instead: every page of a score takes the same path,
 * at the cost of discarding pages already processed.
 */
object CaptureSessionReceiver {
    /**
     * Reads one full session from [connection] and imports it via [importer]. Throws if the session
     * ends before [CaptureSessionMessage.SessionEnd]: importing a partial batch would be worse than a
     * clear failure, and re-sending is cheap.
     *
     * @param processingClient `null` means always import raw, without trying the service.
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

                is CaptureSessionMessage.SessionStart -> {
                    error("received a second SessionStart mid-session")
                }
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

    /** Every photo processed, or `null` (fall back to raw import) if the service is down or any photo fails. */
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
