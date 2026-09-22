package app.inkstave.shared.importer

import app.inkstave.shared.format.Manifest
import app.inkstave.shared.format.SmpkWriter
import app.inkstave.shared.index.LibraryIndexRepository
import java.io.File
import java.time.Instant

/**
 * The M1 import entry point: given raw PDF or image bytes, renders/decodes
 * them to pages (`PlatformImport.*.kt`), builds a score
 * ([ImportPipeline]), writes it into [libraryDirectory] as a `.smpk`
 * (`SmpkWriter`), and upserts it into [index] (ADR-0005) so the library
 * screen reflects it immediately without a restart or a rescan.
 *
 * Owns the one place all three of those steps happen together, so a caller
 * (the library screen's import action) can't accidentally do one without
 * the others and leave the index out of sync with the files on disk.
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

    /**
     * Imports a set of images, in the order given -- that order becomes the
     * score's page order, so callers must sort [imageFiles] into the
     * intended reading order before calling this (M1 has no in-app
     * reordering UI).
     */
    fun importImages(
        title: String,
        imageFiles: List<ByteArray>,
        originalFilename: String? = null,
    ): Manifest {
        val pages = imageFiles.map { decodeImagePage(it) }
        return importDecodedPages(title, "image-import", originalFilename, pages)
    }

    private fun importDecodedPages(
        title: String,
        sourceType: String,
        originalFilename: String?,
        pages: List<DecodedPage>,
    ): Manifest {
        val sourceDetails = originalFilename?.let { mapOf("originalFilename" to it) } ?: emptyMap()
        val imported = ImportPipeline.buildScore(title, sourceType, sourceDetails, pages)

        val destination = File(libraryDirectory, "${imported.manifest.id}.smpk")
        SmpkWriter.write(destination, imported.manifest, imported.part, imported.pages)
        index.upsertFromManifest(imported.manifest, destination.absolutePath, Instant.now().toString())

        return imported.manifest
    }
}
