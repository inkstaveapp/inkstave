package app.inkstave.shared.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.inkstave.shared.format.SmpkReader
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.index.InkstaveDatabase
import app.inkstave.shared.index.LibraryIndexRepository
import app.inkstave.shared.processing.ProcessingServiceClient
import app.inkstave.shared.processing.ProcessingServiceLauncher
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [CaptureSessionReceiver]'s processing policy against a real running `processing-service`. Uses an
 * in-memory [SyncConnection] replaying messages; the transport is covered by
 * [CaptureSessionSyncEndToEndTest].
 */
class CaptureSessionProcessingIntegrationTest {
    private lateinit var libraryDirectory: File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var index: LibraryIndexRepository
    private lateinit var importer: LibraryImporter

    @BeforeTest
    fun setUp() {
        libraryDirectory = createTempDirectory("capture-processing-library-").toFile()
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        InkstaveDatabase.Schema.create(driver)
        index = LibraryIndexRepository(driver)
        importer = LibraryImporter(libraryDirectory, index)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        libraryDirectory.deleteRecursively()
    }

    /** Replays [messages] in order from [receive]; [send] is unused by the receiver side. */
    private class FakeSyncConnection(
        messages: List<CaptureSessionMessage>,
    ) : SyncConnection {
        private val queue = ArrayDeque(messages)

        override fun send(message: CaptureSessionMessage) = Unit

        override fun receive(): CaptureSessionMessage = queue.removeFirstOrNull() ?: error("no more messages queued")

        override fun close() = Unit
    }

    private fun samplePagePhotoBytes(): ByteArray {
        val canvasSize = 400
        val margin = 40
        val image = BufferedImage(canvasSize, canvasSize, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(70, 70, 70)
        g.fillRect(0, 0, canvasSize, canvasSize)
        g.color = Color(245, 245, 245)
        g.fillRect(margin, margin, canvasSize - 2 * margin, canvasSize - 2 * margin)
        g.color = Color(20, 20, 20)
        g.drawRect(margin, margin, canvasSize - 2 * margin - 1, canvasSize - 2 * margin - 1)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun sessionMessages(
        sessionId: String,
        photoCount: Int,
    ): List<CaptureSessionMessage> =
        listOf(CaptureSessionMessage.SessionStart(sessionId, "Moonlight Sonata")) +
            (0 until photoCount).map { CaptureSessionMessage.Photo(sessionId, it, samplePagePhotoBytes()) } +
            CaptureSessionMessage.SessionEnd(sessionId)

    @Test
    fun `a session is processed through a real running processing-service, not imported raw`() {
        val client = ProcessingServiceClient()
        ProcessingServiceLauncher.ensureRunningInBackground(client)
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline && !client.isHealthy()) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        check(client.isHealthy()) { "processing-service never became healthy -- see ProcessingServiceClientTest's setUp doc" }

        val connection = FakeSyncConnection(sessionMessages("session-1", photoCount = 2))
        val outcome = CaptureSessionReceiver.receiveAndImport(connection, importer, client)

        val processed = assertIs<CaptureSessionImportOutcome.Processed>(outcome, "outcome: $outcome")
        assertEquals("Moonlight Sonata", processed.manifest.title)

        // Really processed, not just imported: page metadata carries the pipeline's output, which is
        // null under raw import.
        val libraryRow = index.listAll().single { it.id == processed.manifest.id }
        SmpkReader(File(libraryRow.filePath)).use { reader ->
            val part = reader.readPart(reader.readManifest().parts.single())
            assertEquals(2, part.pageOrder.size)
            for (pageId in part.pageOrder) {
                val meta = reader.readPageMeta(pageId)
                val processing =
                    assertNotNull(meta.processing, "page $pageId must have real processing metadata, not null (raw-import shape)")
                assertEquals("coons-boundary-v1", processing.dewarpMeshVersion)
                assertNotNull(meta.ocr, "page $pageId must have real OCR metadata, not null (raw-import shape)")
                // aspectRatioClass is "custom" here legitimately (the square fixture is neither A4 nor
                // Letter); the processing/ocr checks above are what prove processing happened.
            }
        }
    }

    @Test
    fun `an unreachable processing-service falls back to raw import, not a failure`() {
        val unreachableClient = ProcessingServiceClient(URI.create("http://127.0.0.1:1"))
        val connection = FakeSyncConnection(sessionMessages("session-2", photoCount = 1))

        val outcome = CaptureSessionReceiver.receiveAndImport(connection, importer, unreachableClient)

        val raw = assertIs<CaptureSessionImportOutcome.ImportedRaw>(outcome, "outcome: $outcome")
        assertEquals("Moonlight Sonata", raw.manifest.title)
        val libraryRow = index.listAll().single { it.id == raw.manifest.id }
        SmpkReader(File(libraryRow.filePath)).use { reader ->
            val part = reader.readPart(reader.readManifest().parts.single())
            val meta = reader.readPageMeta(part.pageOrder.single())
            assertNull(meta.processing, "raw-imported page must not carry processing metadata it never actually got")
        }
    }

    @Test
    fun `no processing client configured falls back to raw import without even attempting a health check`() {
        val connection = FakeSyncConnection(sessionMessages("session-3", photoCount = 1))

        val outcome = CaptureSessionReceiver.receiveAndImport(connection, importer, processingClient = null)

        val raw = assertIs<CaptureSessionImportOutcome.ImportedRaw>(outcome, "outcome: $outcome")
        assertEquals("no processing-service configured for this device", raw.reason)
    }

    private companion object {
        const val STARTUP_TIMEOUT_MILLIS = 20_000L
        const val POLL_INTERVAL_MILLIS = 300L
    }
}
