package app.inkstave.shared.importer

import app.inkstave.shared.format.PageOcr
import app.inkstave.shared.format.PageProcessing

/**
 * A page cleaned up by `processing-service` (unlike a raw [DecodedPage]). [pngBytes] is the cleaned
 * image; [processing] and [ocr] carry the pipeline's metadata.
 */
data class ProcessedPage(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val aspectRatioClass: String,
    val processing: PageProcessing,
    val ocr: PageOcr,
)
