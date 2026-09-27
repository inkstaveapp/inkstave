package app.inkstave.shared.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.importer.PickedFile
import app.inkstave.shared.index.InkstaveDatabase
import app.inkstave.shared.index.LibraryIndexRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Android instrumented counterpart to the desktop `AppUiTest`: import a PDF, see it in the library,
 * open it, turn pages, against the real composables on a device.
 *
 * Not wired into the build and never run: `shared/build.gradle.kts` declares no device-test source
 * set or dependencies (androidx.test runner/junit, Compose `ui-test-junit4`). With the
 * `com.android.kotlin.multiplatform.library` plugin that source set is `androidDeviceTest`.
 */
@RunWith(AndroidJUnit4::class)
class AppInstrumentedTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var libraryDir: File
    private lateinit var driver: AndroidSqliteDriver
    private lateinit var index: LibraryIndexRepository
    private lateinit var importer: LibraryImporter

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        libraryDir = File(context.cacheDir, "inkstave-ui-test-${System.nanoTime()}").apply { mkdirs() }
        driver = AndroidSqliteDriver(InkstaveDatabase.Schema, context, name = null) // in-memory: name = null
        index = LibraryIndexRepository(driver)
        importer = LibraryImporter(libraryDir, index)
    }

    @After
    fun tearDown() {
        driver.close()
        libraryDir.deleteRecursively()
    }

    /** A minimal single-page PDF built in-test (Android has no PDFBox). Its xref offsets are
     * placeholders and unchecked against `PdfRenderer`, so suspect this fixture first if the test
     * fails while parsing. */
    private fun tinyOnePagePdfBytes(): ByteArray {
        val pdf =
            """
            %PDF-1.4
            1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
            2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
            3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj
            xref
            0 4
            0000000000 65535 f
            trailer<</Size 4/Root 1 0 R>>
            startxref
            0
            %%EOF
            """.trimIndent()
        return pdf.toByteArray()
    }

    @Test
    fun importAPdfViaTheUiSeeItInTheLibraryOpenItAndTurnPages() {
        composeTestRule.setContent {
            App(
                libraryIndex = index,
                importer = importer,
                pickPdf = { PickedFile(bytes = tinyOnePagePdfBytes(), displayName = "Sonata.pdf") },
                pickImages = { emptyList() },
            )
        }

        composeTestRule.onNodeWithTag(TestTags.EMPTY_LIBRARY).assertExists()

        composeTestRule.onNodeWithTag(TestTags.IMPORT_FAB).performClick()
        composeTestRule.onNodeWithTag(TestTags.IMPORT_PDF_MENU_ITEM).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Sonata").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Sonata").assertExists()

        composeTestRule.onNodeWithText("Sonata").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TestTags.VIEWER_PAGE_INDICATOR).assertTextEquals("1 / 1")

        composeTestRule.onNodeWithTag(TestTags.VIEWER_BACK_BUTTON).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Sonata").assertExists()
    }
}
