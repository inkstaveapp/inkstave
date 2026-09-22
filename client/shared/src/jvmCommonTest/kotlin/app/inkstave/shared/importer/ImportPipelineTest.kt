package app.inkstave.shared.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Unit tests for [ImportPipeline.buildScore]'s manifest/part/page assembly. */
class ImportPipelineTest {
    private val syntheticPages =
        listOf(
            DecodedPage(pngBytes = byteArrayOf(1), width = 100, height = 200),
            DecodedPage(pngBytes = byteArrayOf(2), width = 100, height = 200),
            DecodedPage(pngBytes = byteArrayOf(3), width = 100, height = 200),
        )

    @Test
    fun `builds a single-part score with pages in the given order`() {
        val result =
            ImportPipeline.buildScore(
                title = "Test Score",
                sourceType = "image-import",
                sourceDetails = emptyMap(),
                decodedPages = syntheticPages,
            )

        assertEquals("Test Score", result.manifest.title)
        assertEquals(listOf("part-1"), result.manifest.parts)
        assertEquals(listOf("page-1", "page-2", "page-3"), result.part.pageOrder)
        assertEquals(3, result.pages.size)
        assertEquals("page-2", result.pages[1].id)
        assertEquals(byteArrayOf(2).toList(), result.pages[1].pngBytes.toList())
    }

    @Test
    fun `marks every page as custom aspect ratio since no normalization has run`() {
        val result = ImportPipeline.buildScore("Test", "pdf-import", emptyMap(), syntheticPages)

        result.pages.forEach { page -> assertEquals("custom", page.meta.aspectRatioClass) }
    }

    @Test
    fun `rejects an empty page list`() {
        assertFailsWith<IllegalArgumentException> {
            ImportPipeline.buildScore("Empty", "image-import", emptyMap(), emptyList())
        }
    }

    @Test
    fun `carries source details through to the manifest`() {
        val result =
            ImportPipeline.buildScore(
                title = "Test",
                sourceType = "pdf-import",
                sourceDetails = mapOf("originalFilename" to "clair-de-lune.pdf"),
                decodedPages = syntheticPages,
            )

        assertEquals("pdf-import", result.manifest.source.type)
        assertEquals(
            "clair-de-lune.pdf",
            result.manifest.source.details["originalFilename"]
                ?.toString()
                ?.trim('"'),
        )
    }
}
