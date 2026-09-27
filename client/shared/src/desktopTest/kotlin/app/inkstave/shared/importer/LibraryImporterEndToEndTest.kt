package app.inkstave.shared.importer

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.inkstave.shared.format.SmpkReader
import app.inkstave.shared.index.InkstaveDatabase
import app.inkstave.shared.index.LibraryIndexRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless import -> index -> read scenario (import a PDF, see it listed, open it, turn pages),
 * below the UI; the UI-level version is `AppUiTest`.
 */
class LibraryImporterEndToEndTest {
    private lateinit var libraryDir: File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var index: LibraryIndexRepository
    private lateinit var importer: LibraryImporter

    @BeforeTest
    fun setUp() {
        libraryDir =
            kotlin.io.path
                .createTempDirectory("inkstave-library-")
                .toFile()
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        InkstaveDatabase.Schema.create(driver)
        index = LibraryIndexRepository(driver)
        importer = LibraryImporter(libraryDir, index)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        libraryDir.deleteRecursively()
    }

    private fun samplePdfBytes(pageCount: Int): ByteArray {
        PDDocument().use { document ->
            repeat(pageCount) { document.addPage(PDPage(PDRectangle.A4)) }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun `import a PDF, see it in the library, open it, and read pages in order`() {
        // Import a score from a single PDF.
        val manifest = importer.importPdf(title = "Sonata", pdfBytes = samplePdfBytes(pageCount = 4))

        // The library lists from the index, not by re-scanning .smpk files (ADR-0005).
        val libraryRow = index.listAll().singleOrNull { it.id == manifest.id }
        assertTrue(libraryRow != null, "imported score must appear in the library index immediately")
        assertEquals("Sonata", libraryRow.title)

        // Read it as the viewer would: manifest -> the score's part -> each page's bytes in order.
        SmpkReader(File(libraryRow.filePath)).use { reader ->
            val part = reader.readPart(reader.readManifest().parts.single())
            assertEquals(4, part.pageOrder.size)

            part.pageOrder.forEach { pageId ->
                val pageBytes = reader.readPageBytes(pageId)
                assertTrue(pageBytes.isNotEmpty(), "page $pageId must have non-empty rendered bytes")
            }
        }
    }

    private fun samplePngBytes(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    @Test
    fun `import a set of images, in the given order, as one score`() {
        // Import a score from a set of images.
        val manifest =
            importer.importImages(
                title = "Image Score",
                imageFiles = listOf(samplePngBytes(20, 30), samplePngBytes(20, 30)),
            )

        val libraryRow = index.listAll().singleOrNull { it.id == manifest.id }
        assertTrue(libraryRow != null)

        SmpkReader(File(libraryRow.filePath)).use { reader ->
            assertEquals(2, reader.readPart("part-1").pageOrder.size)
        }
    }
}
