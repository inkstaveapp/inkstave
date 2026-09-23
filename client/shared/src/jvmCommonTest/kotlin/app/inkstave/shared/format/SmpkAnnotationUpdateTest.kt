package app.inkstave.shared.format

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [SmpkReader.readAnnotationLayer] and [SmpkUpdater]
 * -- the M2 gap M1's container support left open. Covers exactly the
 * regression [SmpkUpdater]'s rewrite-based approach could introduce if done
 * carelessly: updating one page's annotations must never disturb any other
 * entry in the package.
 */
class SmpkAnnotationUpdateTest {
    private fun scoreWithPages(vararg pageIds: String): File {
        val tempFile = File.createTempFile("inkstave-anno-test-", ".smpk")
        tempFile.deleteOnExit()
        val manifest =
            Manifest(
                formatVersion = 1,
                id = "score-1",
                title = "Test Score",
                createdAt = "2026-09-23T00:00:00Z",
                modifiedAt = "2026-09-23T00:00:00Z",
                parts = listOf("part-1"),
                source = ManifestSource(type = "image-import"),
            )
        val part = Part(id = "part-1", name = "Full Score", pageOrder = pageIds.toList())
        val pages =
            pageIds.map { id ->
                SmpkPage(
                    id = id,
                    // Distinct bytes per page (not just a repeated pattern) so a test
                    // that mixes up which page's bytes it's comparing would fail loudly.
                    pngBytes = ByteArray(64) { (it + id.hashCode()).toByte() },
                    meta = PageMeta(id = id, width = 100, height = 200, aspectRatioClass = "custom"),
                )
            }
        SmpkWriter.write(tempFile, manifest, part, pages)
        return tempFile
    }

    @Test
    fun `readAnnotationLayer returns an empty layer for a page that was never annotated`() {
        val file = scoreWithPages("page-1")

        SmpkReader(file).use { reader ->
            val layer = reader.readAnnotationLayer("page-1")
            assertEquals("page-1", layer.pageId)
            assertTrue(layer.strokes.isEmpty() && layer.stamps.isEmpty() && layer.highlights.isEmpty() && layer.textNotes.isEmpty())
        }
    }

    @Test
    fun `updateAnnotationLayer then read round-trips the written layer`() {
        val file = scoreWithPages("page-1")
        val layer =
            AnnotationLayer(
                pageId = "page-1",
                layerVersion = 1,
                strokes = listOf(Stroke(id = "s1", points = listOf(listOf(1.0, 2.0), listOf(3.0, 4.0)), color = "#000000", widthPt = 2.0)),
                stamps = listOf(Stamp(id = "st1", symbol = "fermata", x = 10.0, y = 20.0, scale = 1.0, rotationDeg = 0.0)),
            )

        SmpkUpdater.updateAnnotationLayer(file, layer)

        SmpkReader(file).use { reader ->
            val read = reader.readAnnotationLayer("page-1")
            assertEquals(1, read.strokes.size)
            assertEquals("s1", read.strokes.single().id)
            assertEquals(1, read.stamps.size)
            assertEquals("fermata", read.stamps.single().symbol)
        }
    }

    @Test
    fun `updating one page's annotations leaves every other page's bytes untouched`() {
        val file = scoreWithPages("page-1", "page-2", "page-3")
        val originalBytes =
            SmpkReader(file).use { reader ->
                mapOf(
                    "page-1" to reader.readPageBytes("page-1"),
                    "page-2" to reader.readPageBytes("page-2"),
                    "page-3" to reader.readPageBytes("page-3"),
                )
            }
        val originalManifest = SmpkReader(file).use { it.readManifest() }
        val originalPart = SmpkReader(file).use { it.readPart("part-1") }

        SmpkUpdater.updateAnnotationLayer(
            file,
            AnnotationLayer(
                pageId = "page-2",
                layerVersion = 1,
                textNotes = listOf(TextNote(id = "n1", x = 5.0, y = 5.0, text = "hi", fontSizePt = 10.0)),
            ),
        )

        SmpkReader(file).use { reader ->
            // The updated page's annotation layer is actually there.
            assertEquals(1, reader.readAnnotationLayer("page-2").textNotes.size)
            // Every page's other data -- including the updated page's own PNG bytes --
            // survives byte-for-byte, since only the annotations/page-2.json entry
            // should have been touched by the rewrite.
            assertContentEquals(originalBytes.getValue("page-1"), reader.readPageBytes("page-1"))
            assertContentEquals(originalBytes.getValue("page-2"), reader.readPageBytes("page-2"))
            assertContentEquals(originalBytes.getValue("page-3"), reader.readPageBytes("page-3"))
            // Unaffected pages have no annotation entry at all -- still the empty default.
            assertTrue(reader.readAnnotationLayer("page-1").textNotes.isEmpty())
            assertTrue(reader.readAnnotationLayer("page-3").textNotes.isEmpty())
            // Manifest and part are unaffected too.
            assertEquals(originalManifest.id, reader.readManifest().id)
            assertEquals(originalPart.pageOrder, reader.readPart("part-1").pageOrder)
        }
    }

    @Test
    fun `updating the same page's annotations twice replaces rather than duplicates the entry`() {
        val file = scoreWithPages("page-1")

        SmpkUpdater.updateAnnotationLayer(
            file,
            AnnotationLayer(pageId = "page-1", layerVersion = 1, textNotes = listOf(TextNote("n1", 0.0, 0.0, "first", 10.0))),
        )
        SmpkUpdater.updateAnnotationLayer(
            file,
            AnnotationLayer(pageId = "page-1", layerVersion = 1, textNotes = listOf(TextNote("n2", 0.0, 0.0, "second", 10.0))),
        )

        SmpkReader(file).use { reader ->
            val layer = reader.readAnnotationLayer("page-1")
            assertEquals(1, layer.textNotes.size, "a second update must replace, not append, the annotations entry")
            assertEquals("second", layer.textNotes.single().text)
        }
    }
}
