package app.inkstave.shared.importer

import app.inkstave.shared.format.Manifest
import app.inkstave.shared.format.SmpkWriter
import app.inkstave.shared.index.LibraryIndexRepository
import java.io.File
import java.time.Instant

/**
 * Imports PDFs, images or processed pages: builds the score, writes it to [libraryDirectory] as a
 * `.smpk`, and updates [index]. Writing and indexing always happen together here, so the index
 * can't fall out of sync with the files (ADR-0005).
 */
class LibraryImporter(
    private val libraryDirectory: File,
    private val index: LibraryIndexRepository,
) {
    /** Imports a PDF: renders every page, then delegates to [importDecodedPages]. */
    fun importPdf(
        title: String,
        pdfBytes: ByteArray,
        originalFilename: String? = null,
    ): Manifest {
        val pages = renderPdfPages(pdfBytes)
        return importDecodedPages(title, "pdf-import", originalFilename, pages)
    }

    /** Imports images as pages, in the order given: callers must pass them in reading order. */
    fun importImages(
        title: String,
        imageFiles: List<ByteArray>,
        originalFilename: String? = null,
    ): Manifest {
        val pages = imageFiles.map { decodeImagePage(it) }
        return importDecodedPages(title, "image-import", originalFilename, pages)
    }

    /**
     * Imports pages already cleaned up by `processing-service` as a `"camera-capture"` score, keeping
     * their processing and OCR metadata. When the service is unreachable, callers use [importImages].
     */
    fun importProcessedPages(
        title: String,
        processedPages: List<ProcessedPage>,
        originalFilename: String? = null,
    ): Manifest {
        val sourceDetails = originalFilename?.let { mapOf("originalFilename" to it) } ?: emptyMap()
        val imported = ImportPipeline.buildProcessedScore(title, sourceDetails, processedPages)
        return writeAndIndex(imported)
    }

    private fun importDecodedPages(
        title: String,
        sourceType: String,
        originalFilename: String?,
        pages: List<DecodedPage>,
    ): Manifest {
        val sourceDetails = originalFilename?.let { mapOf("originalFilename" to it) } ?: emptyMap()
        val imported = ImportPipeline.buildScore(title, sourceType, sourceDetails, pages)
        return writeAndIndex(imported)
    }

    /** Writes the `.smpk` and updates the index, together. */
    private fun writeAndIndex(imported: ImportedScore): Manifest {
        val destination = File(libraryDirectory, "${imported.manifest.id}.smpk")
        SmpkWriter.write(destination, imported.manifest, imported.part, imported.pages)
        index.upsertFromManifest(imported.manifest, destination.absolutePath, Instant.now().toString())
        return imported.manifest
    }
}
