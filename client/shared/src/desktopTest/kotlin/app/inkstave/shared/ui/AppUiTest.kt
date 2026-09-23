package app.inkstave.shared.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.importer.PickedFile
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

/**
 * Closes `docs/testing-strategy.md`'s M1 "UI-level end-to-end" gap for the
 * desktop target (see `ROADMAP.md`'s M1 entry): drives the real
 * [App]/[LibraryScreen]/[ViewerScreen] composables through real simulated UI
 * interaction (clicks on real nodes, not direct function calls), against a
 * real [LibraryImporter] and a real (in-memory) SQLite [LibraryIndexRepository]
 * -- only the platform file picker itself is stubbed, since driving a real OS
 * file dialog isn't something a UI test can or should do; that's exactly the
 * one seam [App] already takes as a parameter for this reason.
 *
 * Runs headlessly via Compose Multiplatform's `runComposeUiTest`, which
 * renders through Skiko's software rasterizer -- no display server required,
 * so this runs identically in CI (`.github/workflows/ci.yml`) as on a
 * developer machine, unlike a screenshot-based approach would.
 */
@OptIn(ExperimentalTestApi::class)
class AppUiTest {
    private lateinit var libraryDir: File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var index: LibraryIndexRepository
    private lateinit var importer: LibraryImporter

    @BeforeTest
    fun setUp() {
        libraryDir =
            kotlin.io.path
                .createTempDirectory("inkstave-ui-test-")
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
    fun `import a PDF via the UI, see it in the library, open it, and turn pages`() =
        runComposeUiTest {
            setContent {
                App(
                    libraryIndex = index,
                    importer = importer,
                    pickPdf = { PickedFile(bytes = samplePdfBytes(pageCount = 3), displayName = "Sonata.pdf") },
                    pickImages = { emptyList() },
                )
            }

            // Starts empty -- "basic library screen" (ROADMAP.md M1) with nothing imported yet.
            onNodeWithTag(TestTags.EMPTY_LIBRARY).assertExists()

            // "Import a score from a single PDF" -- through the real FAB -> dropdown ->
            // pickPdf -> LibraryImporter.importPdf path, not a direct function call.
            onNodeWithTag(TestTags.IMPORT_FAB).performClick()
            onNodeWithTag(TestTags.IMPORT_PDF_MENU_ITEM).performClick()

            // The import runs on Dispatchers.IO (LibraryScreen.runImport) -- a real
            // background dispatcher runComposeUiTest's virtual clock doesn't control --
            // so wait against real time for it to land, rather than a bare
            // waitForIdle() that could return before the background work finishes.
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("Sonata").fetchSemanticsNodes().isNotEmpty()
            }

            // "See it in the library" -- reflects the index (ADR-0005), not a directory scan.
            onNodeWithText("Sonata").assertExists()

            // "Open it."
            onNodeWithText("Sonata").performClick()
            waitForIdle()
            onNodeWithTag(TestTags.VIEWER_PAGE_INDICATOR).assertTextEquals("1 / 3")

            // "Turn pages" -- via the real tap zone, the same input a touch user has.
            onNodeWithTag(TestTags.VIEWER_TAP_ZONE_NEXT).performClick()
            waitForIdle()
            onNodeWithTag(TestTags.VIEWER_PAGE_INDICATOR).assertTextEquals("2 / 3")

            // Back to the library, same score still listed.
            onNodeWithTag(TestTags.VIEWER_BACK_BUTTON).performClick()
            waitForIdle()
            onNodeWithText("Sonata").assertExists()
        }
}
