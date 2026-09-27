package app.inkstave.shared.index

import app.cash.sqldelight.db.SqlDriver
import app.inkstave.shared.format.Manifest

/** One row of the local library index (ADR-0005): a disposable cache of a `.smpk`'s manifest fields. */
data class ScoreSummary(
    val id: String,
    val filePath: String,
    val title: String,
    val subtitle: String?,
    val composer: String?,
    val arranger: String?,
    val lyricist: String?,
    val genre: String?,
    val tags: List<String>,
    val createdAt: String,
    val modifiedAt: String,
)

/**
 * Typed access to the library index, and the only place manifest fields map to index rows (e.g.
 * tags to the comma-joined `tagsCsv`). [driver] comes from the platform's `*SqlDriverFactory`.
 */
class LibraryIndexRepository(
    driver: SqlDriver,
) {
    private val database = InkstaveDatabase(driver)

    /**
     * Inserts or replaces this score's index row. Every write that changes a `.smpk`'s manifest
     * (import, metadata edit, sync) must call this too, to keep the index in sync (ADR-0005).
     */
    fun upsertFromManifest(
        manifest: Manifest,
        filePath: String,
        indexedAt: String,
    ) {
        database.scoreIndexQueries.upsert(
            id = manifest.id,
            filePath = filePath,
            title = manifest.title,
            subtitle = manifest.subtitle,
            composer = manifest.composer,
            arranger = manifest.arranger,
            lyricist = manifest.lyricist,
            genre = manifest.genre,
            tagsCsv = manifest.tags.joinToString(","),
            createdAt = manifest.createdAt,
            modifiedAt = manifest.modifiedAt,
            indexedAt = indexedAt,
        )
    }

    /** Every indexed score, alphabetical by title. */
    fun listAll(): List<ScoreSummary> =
        database.scoreIndexQueries
            .selectAll()
            .executeAsList()
            .map { it.toSummary() }

    /** Scores whose title or composer contains [query], case-insensitively. */
    fun search(query: String): List<ScoreSummary> =
        database.scoreIndexQueries
            .searchByTitleOrComposer(query)
            .executeAsList()
            .map { it.toSummary() }

    /** Removes a score's index row. Always safe: the index is a rebuildable cache (ADR-0005). */
    fun remove(id: String) = database.scoreIndexQueries.deleteById(id)

    private fun ScoreIndexEntry.toSummary() =
        ScoreSummary(
            id = id,
            filePath = filePath,
            title = title,
            subtitle = subtitle,
            composer = composer,
            arranger = arranger,
            lyricist = lyricist,
            genre = genre,
            tags = if (tagsCsv.isEmpty()) emptyList() else tagsCsv.split(","),
            createdAt = createdAt,
            modifiedAt = modifiedAt,
        )
}
