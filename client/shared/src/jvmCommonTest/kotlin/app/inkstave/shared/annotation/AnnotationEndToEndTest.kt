package app.inkstave.shared.annotation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.inkstave.shared.format.AnnotationLayer
import app.inkstave.shared.format.SmpkReader
import app.inkstave.shared.format.SmpkUpdater
import app.inkstave.shared.format.Stroke
import app.inkstave.shared.importer.LibraryImporter
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
 * Headless annotate-and-reopen scenario: import -> annotate -> save -> reopen, checking the annotation
 * persists in the right place. Covers persistence only, not
 * [app.inkstave.shared.ui.AnnotationOverlay]'s gesture handling.
 */
class AnnotationEndToEndTest {
    private lateinit var libraryDir: File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var index: LibraryIndexRepository
    private lateinit var importer: LibraryImporter

    @BeforeTest
    fun setUp() {
        libraryDir =
            kotlin.io.path
                .createTempDirectory("inkstave-anno-e2e-")
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
    fun `annotate a page, close and reopen the score, the annotation is still there and correctly placed`() {
        // Import a score.
        val manifest = importer.importPdf(title = "Sonata", pdfBytes = samplePdfBytes(pageCount = 2))
        val scoreFile = File(index.listAll().single { it.id == manifest.id }.filePath)

        // A page with no annotations yet must read back empty, not throw.
        val firstPageId =
            SmpkReader(scoreFile).use { reader ->
                val part = reader.readPart(reader.readManifest().parts.single())
                assertTrue(reader.readAnnotationLayer(part.pageOrder.first()).strokes.isEmpty())
                part.pageOrder.first()
            }

        // Draw a stroke as the pen tool would, run it through undo/redo history, and save via
        // SmpkUpdater (debounced in the UI, called directly here).
        val history = AnnotationHistory(initial = AnnotationLayer.empty(firstPageId))
        val stroke =
            Stroke(
                id = "stroke-1",
                points = listOf(listOf(100.0, 100.0), listOf(150.0, 120.0), listOf(200.0, 100.0)),
                color = "#000000",
                widthPt = 2.0,
            )
        history.push(history.current.copy(strokes = listOf(stroke)))
        SmpkUpdater.updateAnnotationLayer(scoreFile, history.current)

        // Reopen with a fresh SmpkReader, sharing no in-memory state with the write.
        SmpkReader(scoreFile).use { reader ->
            val layer = reader.readAnnotationLayer(firstPageId)
            assertEquals(1, layer.strokes.size, "the annotation must still be there after reopening")
            assertEquals(stroke.points, layer.strokes.single().points, "and in the right place -- exact point coordinates preserved")
        }

        // The other page must stay unannotated: the update is scoped to the edited page.
        SmpkReader(scoreFile).use { reader ->
            val part = reader.readPart(reader.readManifest().parts.single())
            val secondPageId = part.pageOrder[1]
            assertTrue(reader.readAnnotationLayer(secondPageId).strokes.isEmpty())
        }
    }
}
