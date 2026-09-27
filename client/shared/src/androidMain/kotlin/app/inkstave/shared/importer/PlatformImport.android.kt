package app.inkstave.shared.importer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Android [renderPdfPages] using the platform's built-in [PdfRenderer], so no PDF library is
 * needed here. [PdfRenderer] only opens a [ParcelFileDescriptor], so [pdfBytes] go through a temp file.
 */
actual fun renderPdfPages(pdfBytes: ByteArray): List<DecodedPage> {
    require(pdfBytes.isNotEmpty()) { "pdfBytes must not be empty" }
    val tempFile = File.createTempFile("inkstave-import-", ".pdf")
    try {
        tempFile.writeBytes(pdfBytes)
        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer =
                try {
                    PdfRenderer(pfd)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Not a valid PDF", e)
                }
            renderer.use {
                return (0 until renderer.pageCount).map { index -> renderer.renderPage(index) }
            }
        }
    } finally {
        tempFile.delete()
    }
}

/** Scale from a [PdfRenderer.Page]'s intrinsic point size (72dpi) up to [PAGE_RENDER_DPI]. */
private fun PdfRenderer.renderPage(index: Int): DecodedPage {
    openPage(index).use { page ->
        val scale = PAGE_RENDER_DPI / 72f
        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // White background: PdfRenderer only draws page content, and a page with
        // transparent margins would otherwise composite as black in some viewers.
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap.toPngPage()
    }
}

/** Android [decodeImagePage] using [BitmapFactory] (PNG, JPEG, WebP, ...). */
actual fun decodeImagePage(imageBytes: ByteArray): DecodedPage {
    val bitmap =
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalArgumentException("Not a decodable image (BitmapFactory returned null)")
    return bitmap.toPngPage()
}

private fun Bitmap.toPngPage(): DecodedPage {
    val out = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 100, out)
    return DecodedPage(pngBytes = out.toByteArray(), width = width, height = height)
}
