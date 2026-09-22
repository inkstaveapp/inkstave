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
 * The headless equivalent of M1's headline end-to-end scenario
 * (`docs/testing-strategy.md`: "import a PDF, see it in the library, open
 * it, turn pages"), driving the real import/index/read code path without
 * going through actual UI widgets.
 *
 * **Known gap, disclosed rather than silently skipped:** this proves the
 * pipeline underneath the UI works end-to-end, but it is not a UI-level
 * test (no Compose UI, no real Android instrumentation, no real file
 * picker). True UI-level e2e automation (Android instrumented tests,
 * desktop UI-driver automation) is not built in this pass -- see the M1
 * completion report / `ROADMAP.md`.
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
        // "Import a score from a single PDF" (ROADMAP.md M1).
        val manifest = importer.importPdf(title = "Sonata", pdfBytes = samplePdfBytes(pageCount = 4))

        // "See it in the library" -- the library screen lists from the index, not by
        // re-scanning .smpk files (ADR-0005, docs/performance.md).
        val libraryRow = index.listAll().singleOrNull { it.id == manifest.id }
        assertTrue(libraryRow != null, "imported score must appear in the library index immediately")
        assertEquals("Sonata", libraryRow.title)

        // "Open it, turn pages" -- read the .smpk the same way the viewer screen would:
        // manifest -> the score's one part -> each page's bytes, in page order.
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
        // "Import a score from a set of images" (ROADMAP.md M1).
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
