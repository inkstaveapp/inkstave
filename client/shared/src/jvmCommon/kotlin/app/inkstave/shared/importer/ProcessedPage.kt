package app.inkstave.shared.importer

import app.inkstave.shared.format.PageOcr
import app.inkstave.shared.format.PageProcessing

/**
 * One page that has already been run through `processing-service`'s cleanup/OCR pipeline
 * (`docs/image-pipeline.md`), as opposed to [DecodedPage] (a raw, as-imported/as-captured page
 * nothing has processed yet). [pngBytes] is the pipeline's *cleaned* output, not the original
 * capture -- see `app.inkstave.shared.processing.ProcessingServiceClient` for where this comes
 * from. [processing]/[ocr] carry the pipeline's real reproducibility/OCR-candidate data, unlike
 * [DecodedPage]-derived pages, which always get `null` for both (`docs/format-spec.md`'s
 * `pages/<page-id>.meta.json`: those fields are `null` until something has actually processed the
 * page).
 */
data class ProcessedPage(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val aspectRatioClass: String,
    val processing: PageProcessing,
    val ocr: PageOcr,
)
