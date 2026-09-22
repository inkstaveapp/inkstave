package app.inkstave.shared.importer

import app.inkstave.shared.format.Manifest
import app.inkstave.shared.format.ManifestSource
import app.inkstave.shared.format.PageMeta
import app.inkstave.shared.format.Part
import app.inkstave.shared.format.SmpkPage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/** A freshly-imported score, ready for [app.inkstave.shared.format.SmpkWriter]. */
data class ImportedScore(
    val manifest: Manifest,
    val part: Part,
    val pages: List<SmpkPage>,
)

/**
 * Turns a list of already-decoded/rendered [DecodedPage]s into the
 * [Manifest]/[Part]/[SmpkPage] triple a `.smpk` needs
 * (`docs/format-spec.md`). M1 always produces a single-part score named
 * "Full Score" -- there's no UI yet for naming parts or importing more than
 * one per score (that's M5, `ROADMAP.md`).
 *
 * Page ids are assigned `page-1`, `page-2`, ... in the order [decodedPages]
 * were provided, which callers must already have in the score's intended
 * reading order (page reordering isn't an M1 feature).
 */
object ImportPipeline {
    /**
     * @param title the score's title, as the user typed it or as guessed from
     * the imported file's name -- M1 has no OCR (that's M4), so this is
     * never inferred from the page content itself.
     * @param sourceType `"pdf-import"` or `"image-import"`, per
     * `docs/format-spec.md`'s `manifest.json.source.type`.
     * @param sourceDetails type-specific details for `manifest.json.source.details`
     * (e.g. the original filename) -- deliberately untyped per the format spec.
     */
    fun buildScore(
        title: String,
        sourceType: String,
        sourceDetails: Map<String, String>,
        decodedPages: List<DecodedPage>,
    ): ImportedScore {
        require(decodedPages.isNotEmpty()) { "a score needs at least one page" }

        val now = Instant.now().toString()
        val pageIds = decodedPages.indices.map { index -> "page-${index + 1}" }

        val pages =
            decodedPages.zip(pageIds).map { (decoded, pageId) ->
                SmpkPage(
                    id = pageId,
                    pngBytes = decoded.pngBytes,
                    meta =
                        PageMeta(
                            id = pageId,
                            width = decoded.width,
                            height = decoded.height,
                            // "custom": M1 pages are the as-imported raster, unmodified by
                            // any normalization pass -- that's M4's job (docs/image-pipeline.md).
                            aspectRatioClass = "custom",
                        ),
                )
            }

        val partId = "part-1"
        val part = Part(id = partId, name = "Full Score", pageOrder = pageIds)

        val manifest =
            Manifest(
                formatVersion = 1,
                id = UUID.randomUUID().toString(),
                title = title,
                createdAt = now,
                modifiedAt = now,
                parts = listOf(partId),
                source =
                    ManifestSource(
                        type = sourceType,
                        details = buildJsonObject { sourceDetails.forEach { (key, value) -> put(key, value) } },
                    ),
            )

        return ImportedScore(manifest, part, pages)
    }
}
