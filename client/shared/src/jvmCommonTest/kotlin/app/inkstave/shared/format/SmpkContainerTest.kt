package app.inkstave.shared.format

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [SmpkWriter]/[SmpkReader] round-trip: a `.smpk` with synthetic pages reads back identically. */
class SmpkContainerTest {
    @Test
    fun `write then read round-trips manifest, part, and page bytes`() {
        val tempFile = File.createTempFile("inkstave-test-", ".smpk")
        tempFile.deleteOnExit()

        val manifest =
            Manifest(
                formatVersion = 1,
                id = "score-1",
                title = "Test Score",
                composer = "Test Composer",
                createdAt = "2026-09-23T00:00:00Z",
                modifiedAt = "2026-09-23T00:00:00Z",
                parts = listOf("part-1"),
                source = ManifestSource(type = "image-import"),
            )
        val part = Part(id = "part-1", name = "Full Score", pageOrder = listOf("page-1", "page-2"))
        val pages =
            listOf(
                SmpkPage(
                    id = "page-1",
                    pngBytes = byteArrayOf(1, 2, 3),
                    meta = PageMeta(id = "page-1", width = 100, height = 200, aspectRatioClass = "custom"),
                ),
                SmpkPage(
                    id = "page-2",
                    pngBytes = byteArrayOf(4, 5, 6, 7),
                    meta = PageMeta(id = "page-2", width = 100, height = 200, aspectRatioClass = "custom"),
                ),
            )

        SmpkWriter.write(tempFile, manifest, part, pages)

        SmpkReader(tempFile).use { reader ->
            val readManifest = reader.readManifest()
            assertEquals("Test Score", readManifest.title)
            assertEquals("Test Composer", readManifest.composer)

            val readPart = reader.readPart("part-1")
            assertEquals(listOf("page-1", "page-2"), readPart.pageOrder)

            assertContentEquals(byteArrayOf(1, 2, 3), reader.readPageBytes("page-1"))
            assertContentEquals(byteArrayOf(4, 5, 6, 7), reader.readPageBytes("page-2"))

            val page2Meta = reader.readPageMeta("page-2")
            assertEquals(200, page2Meta.height)
        }
    }

    @Test
    fun `readManifest does not require reading page bytes first`() {
        // The manifest is read by random access, without scanning the whole archive.
        val tempFile = File.createTempFile("inkstave-test-", ".smpk")
        tempFile.deleteOnExit()

        val manifest =
            Manifest(
                formatVersion = 1,
                id = "score-1",
                title = "Only Manifest",
                createdAt = "2026-09-23T00:00:00Z",
                modifiedAt = "2026-09-23T00:00:00Z",
                parts = listOf("part-1"),
                source = ManifestSource(type = "pdf-import"),
            )
        val part = Part(id = "part-1", name = "Full Score", pageOrder = listOf("page-1"))
        val pages =
            listOf(
                SmpkPage(
                    id = "page-1",
                    pngBytes = ByteArray(1024) { it.toByte() },
                    meta = PageMeta(id = "page-1", width = 10, height = 10, aspectRatioClass = "custom"),
                ),
            )
        SmpkWriter.write(tempFile, manifest, part, pages)

        SmpkReader(tempFile).use { reader ->
            assertTrue(reader.readManifest().title == "Only Manifest")
        }
    }
}
