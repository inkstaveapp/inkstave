package app.inkstave.shared.importer

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests the desktop [renderPdfPages]/[decodeImagePage] implementations
 * against real PDFBox/ImageIO output -- per `docs/testing-strategy.md`,
 * generating the PDF fixture programmatically rather than committing a
 * binary file.
 */
class PlatformImportDesktopTest {
    private fun samplePdfBytes(pageCount: Int): ByteArray {
        PDDocument().use { document ->
            repeat(pageCount) { index ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 24f)
                    stream.newLineAtOffset(50f, 700f)
                    stream.showText("Page ${index + 1}")
                    stream.endText()
                }
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun `renderPdfPages produces one page per PDF page with sane dimensions`() {
        val pages = renderPdfPages(samplePdfBytes(pageCount = 3))

        assertEquals(3, pages.size)
        pages.forEach { page ->
            // A4 at PAGE_RENDER_DPI should render to several hundred pixels per side --
            // sanity-checking against an unreasonably small/degenerate raster.
            assertTrue(page.width > 100 && page.height > 100, "page too small: ${page.width}x${page.height}")
            assertTrue(page.pngBytes.isNotEmpty())
        }
    }

    @Test
    fun `renderPdfPages rejects bytes that are not a PDF`() {
        assertFailsWith<IllegalArgumentException> {
            renderPdfPages("not a pdf".encodeToByteArray())
        }
    }

    @Test
    fun `decodeImagePage decodes a PNG and re-encodes it as PNG`() {
        val sourceImage = java.awt.image.BufferedImage(50, 80, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val sourceBytes = ByteArrayOutputStream().also { ImageIO.write(sourceImage, "png", it) }.toByteArray()

        val page = decodeImagePage(sourceBytes)

        assertEquals(50, page.width)
        assertEquals(80, page.height)
        // Re-decode the output to confirm it's valid PNG, not just non-empty bytes.
        val redecoded = ImageIO.read(ByteArrayInputStream(page.pngBytes))
        assertEquals(50, redecoded.width)
    }

    @Test
    fun `decodeImagePage rejects bytes that are not a decodable image`() {
        assertFailsWith<IllegalArgumentException> {
            decodeImagePage("not an image".encodeToByteArray())
        }
    }
}
