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
 * Turns decoded pages into the [Manifest]/[Part]/[SmpkPage] triple a `.smpk` needs
 * (`docs/format-spec.md`). Always produces a single part named "Full Score".
 *
 * Page ids are `page-1`, `page-2`, ... in the order given, so callers must pass pages in
 * reading order.
 */
object ImportPipeline {
    /**
     * @param title the score's title, as typed by the user or guessed from the file name.
     * @param sourceType `"pdf-import"` or `"image-import"`, per
     * `docs/format-spec.md`'s `manifest.json.source.type`.
     * @param sourceDetails type-specific details for `manifest.json.source.details`
     * (e.g. the original filename); untyped per the format spec.
     */
    fun buildScore(
        title: String,
        sourceType: String,
        sourceDetails: Map<String, String>,
        decodedPages: List<DecodedPage>,
    ): ImportedScore {
        require(decodedPages.isNotEmpty()) { "a score needs at least one page" }
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
                            // "custom": imported pages keep their original raster, unnormalized.
                            aspectRatioClass = "custom",
                        ),
                )
            }
        val (manifest, part) = manifestAndPart(title, sourceType, sourceDetails, pageIds)
        return ImportedScore(manifest, part, pages)
    }

    /**
     * Like [buildScore], for pages already run through `processing-service`: each page keeps the
     * pipeline's [PageMeta.processing], [PageMeta.ocr] and aspect-ratio class. Used for received
     * capture sessions, so the source type is always `"camera-capture"`.
     */
    fun buildProcessedScore(
        title: String,
        sourceDetails: Map<String, String>,
        processedPages: List<ProcessedPage>,
    ): ImportedScore {
        require(processedPages.isNotEmpty()) { "a score needs at least one page" }
        val pageIds = processedPages.indices.map { index -> "page-${index + 1}" }
        val pages =
            processedPages.zip(pageIds).map { (processed, pageId) ->
                SmpkPage(
                    id = pageId,
                    pngBytes = processed.pngBytes,
                    meta =
                        PageMeta(
                            id = pageId,
                            width = processed.width,
                            height = processed.height,
                            aspectRatioClass = processed.aspectRatioClass,
                            processing = processed.processing,
                            ocr = processed.ocr,
                        ),
                )
            }
        val (manifest, part) = manifestAndPart(title, "camera-capture", sourceDetails, pageIds)
        return ImportedScore(manifest, part, pages)
    }

    /** Builds the [Manifest] and single [Part] shared by [buildScore] and [buildProcessedScore]. */
    private fun manifestAndPart(
        title: String,
        sourceType: String,
        sourceDetails: Map<String, String>,
        pageIds: List<String>,
    ): Pair<Manifest, Part> {
        val now = Instant.now().toString()
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
        return manifest to part
    }
}
