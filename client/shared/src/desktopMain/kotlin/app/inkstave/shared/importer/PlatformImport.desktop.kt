package app.inkstave.shared.importer

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Desktop [renderPdfPages] using Apache PDFBox, rendering each page at [PAGE_RENDER_DPI] and encoding it as PNG. */
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

/** Desktop [decodeImagePage] using [ImageIO] (PNG, JPEG, ...). */
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
