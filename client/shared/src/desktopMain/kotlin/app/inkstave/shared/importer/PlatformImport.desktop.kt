package app.inkstave.shared.importer

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Desktop implementation of [renderPdfPages] using Apache PDFBox 3.x
 * (`NOTICE.md` -- Apache-2.0). PDFBox's [PDFRenderer.renderImageWithDPI]
 * does the page-to-raster work directly at [PAGE_RENDER_DPI]; each page is
 * then re-encoded to PNG via [BufferedImage.toPngPage].
 */
actual fun renderPdfPages(pdfBytes: ByteArray): List<DecodedPage> {
    require(pdfBytes.isNotEmpty()) { "pdfBytes must not be empty" }
    val document =
        try {
            Loader.loadPDF(pdfBytes)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid PDF", e)
        }
    return document.use { doc ->
        val renderer = PDFRenderer(doc)
        (0 until doc.numberOfPages).map { pageIndex ->
            renderer.renderImageWithDPI(pageIndex, PAGE_RENDER_DPI, ImageType.RGB).toPngPage()
        }
    }
}

/**
 * Desktop implementation of [decodeImagePage] using [ImageIO], which covers
 * PNG/JPEG (and several other common formats) without any extra dependency.
 */
actual fun decodeImagePage(imageBytes: ByteArray): DecodedPage {
    val image =
        ImageIO.read(ByteArrayInputStream(imageBytes))
            ?: throw IllegalArgumentException("Not a decodable image (no ImageIO reader recognised it)")
    return image.toPngPage()
}

private fun BufferedImage.toPngPage(): DecodedPage {
    val out = ByteArrayOutputStream()
    ImageIO.write(this, "png", out)
    return DecodedPage(pngBytes = out.toByteArray(), width = width, height = height)
}
