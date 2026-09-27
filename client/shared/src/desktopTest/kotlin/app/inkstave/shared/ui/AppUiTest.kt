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
import app.inkstave.shared.pedal.PedalKeyMapping
import app.inkstave.shared.sync.PeerTrustStore
import app.inkstave.shared.sync.getOrCreateDeviceIdentity
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Desktop UI end-to-end test: drives the real [App]/[LibraryScreen]/[ViewerScreen] through simulated
 * clicks, against a real [LibraryImporter] and an in-memory SQLite [LibraryIndexRepository]. Only the
 * file picker is stubbed. Runs headlessly on Skiko's software renderer, so it works in CI.
 */
@OptIn(ExperimentalTestApi::class)
class AppUiTest {
    private lateinit var libraryDir: File
    private lateinit var syncDir: File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var index: LibraryIndexRepository
    private lateinit var importer: LibraryImporter

    @BeforeTest
    fun setUp() {
        libraryDir =
            kotlin.io.path
                .createTempDirectory("inkstave-ui-test-")
                .toFile()
        syncDir =
            kotlin.io.path
                .createTempDirectory("inkstave-ui-test-sync-")
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
        syncDir.deleteRecursively()
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
                    // Pedal behaviour is covered by PedalSettingsUiTest.
                    pedalMapping = PedalKeyMapping.DEFAULT,
                    onPedalMappingChange = {},
                    // Pairing isn't exercised here; a real identity/trust store is cheap to provide.
                    syncSettingsDirectory = syncDir,
                    localIdentity = getOrCreateDeviceIdentity(syncDir),
                    peerTrustStore = PeerTrustStore(File(syncDir, "trusted-peers.json")),
                )
            }

            // Starts empty.
            onNodeWithTag(TestTags.EMPTY_LIBRARY).assertExists()

            // Import through the real FAB -> dropdown -> pickPdf path.
            onNodeWithTag(TestTags.IMPORT_FAB).performClick()
            onNodeWithTag(TestTags.IMPORT_PDF_MENU_ITEM).performClick()

            // The import runs on Dispatchers.IO, which the test's virtual clock doesn't control, so
            // wait in real time rather than relying on waitForIdle().
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("Sonata").fetchSemanticsNodes().isNotEmpty()
            }

            // The library list reflects the index (ADR-0005), not a directory scan.
            onNodeWithText("Sonata").assertExists()

            // "Open it."
            onNodeWithText("Sonata").performClick()
            waitForIdle()
            onNodeWithTag(TestTags.VIEWER_PAGE_INDICATOR).assertTextEquals("1 / 3")

            // Turn pages via the real tap zone.
            onNodeWithTag(TestTags.VIEWER_TAP_ZONE_NEXT).performClick()
            waitForIdle()
            onNodeWithTag(TestTags.VIEWER_PAGE_INDICATOR).assertTextEquals("2 / 3")

            // Back to the library, same score still listed.
            onNodeWithTag(TestTags.VIEWER_BACK_BUTTON).performClick()
            waitForIdle()
            onNodeWithText("Sonata").assertExists()
        }
}
