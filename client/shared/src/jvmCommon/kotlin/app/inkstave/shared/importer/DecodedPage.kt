package app.inkstave.shared.importer

/** One decoded page, ready for a `.smpk`. [pngBytes] is always PNG, whatever the source, so the rest of the app handles one codec. */
data class DecodedPage(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
)

/** DPI for rendering PDF pages: legible on phone and desktop screens without bloating `.smpk` files. */
internal const val PAGE_RENDER_DPI = 150f

/**
 * Renders every page of a PDF to [DecodedPage]s, in page order: Android's built-in `PdfRenderer`,
 * Apache PDFBox on desktop.
 *
 * @throws IllegalArgumentException if [pdfBytes] isn't a valid, readable PDF.
 */
expect fun renderPdfPages(pdfBytes: ByteArray): List<DecodedPage>

/**
 * Decodes one image (PNG/JPEG at minimum) into a [DecodedPage], re-encoded as PNG.
 *
 * @throws IllegalArgumentException if [imageBytes] isn't a decodable image.
 */
expect fun decodeImagePage(imageBytes: ByteArray): DecodedPage
