package app.inkstave.shared.importer

/**
 * One decoded/rendered page, ready to be written into a `.smpk`
 * (see [app.inkstave.shared.format.SmpkWriter]). [pngBytes] is always
 * PNG-encoded regardless of the source format -- M1 normalizes every
 * imported page to one raster codec at import time (see
 * `docs/format-spec.md`'s `pages/<id>.png` note) so downstream code (the
 * viewer, and eventually the M4 cleanup pipeline) only ever has to handle
 * one codec.
 */
data class DecodedPage(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * The DPI M1 renders PDF pages and re-encodes images at. 150 is a deliberate
 * middle ground for this milestone: legible on typical phone/desktop screen
 * sizes without producing needlessly large `.smpk` files. Not user
 * -configurable yet, and not the same concern as M4's aspect-ratio/size
 * normalization (`docs/image-pipeline.md`) -- this is just "render at a
 * reasonable resolution," not the format's eventual per-device-size story.
 */
internal const val PAGE_RENDER_DPI = 150f

/**
 * Renders every page of a PDF to [DecodedPage]s, in page order.
 * Platform-specific: Android uses the built-in `android.graphics.pdf.PdfRenderer`
 * (`PlatformImport.android.kt`); desktop uses Apache PDFBox
 * (`PlatformImport.desktop.kt`) -- see `docs/decisions/0001-client-framework.md`
 * and `NOTICE.md` for why PDFBox is desktop-only.
 *
 * @throws IllegalArgumentException if [pdfBytes] isn't a valid, readable PDF.
 */
expect fun renderPdfPages(pdfBytes: ByteArray): List<DecodedPage>

/**
 * Decodes one image file's bytes (PNG/JPEG at minimum) into a [DecodedPage],
 * re-encoding to PNG regardless of the source format so every page in the
 * library shares one raster codec.
 *
 * @throws IllegalArgumentException if [imageBytes] isn't a decodable image.
 */
expect fun decodeImagePage(imageBytes: ByteArray): DecodedPage
