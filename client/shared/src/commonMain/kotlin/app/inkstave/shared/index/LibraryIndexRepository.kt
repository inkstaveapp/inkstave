package app.inkstave.shared.index

import app.cash.sqldelight.db.SqlDriver
import app.inkstave.shared.format.Manifest

/**
 * One row of the local library index (ADR-0005,
 * `docs/decisions/0005-local-library-index-database.md`) -- a derived,
 * disposable cache mirroring a `.smpk`'s `manifest.json` fields, typed for
 * UI consumption (the library screen never touches the raw SQLDelight
 * generated row type directly).
 */
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
 * Typed wrapper around the SQLDelight-generated [InkstaveDatabase]. This is
 * the only place `.smpk` manifest fields get translated to/from the index's
 * row shape (e.g. [ScoreSummary.tags] <-> the row's comma-joined `tagsCsv`,
 * per `ScoreIndex.sq`'s comment on that simplification) -- callers work
 * with typed [Manifest]/[ScoreSummary] values, never raw query rows.
 *
 * [driver] is constructed by platform-specific code and passed in here:
 * Android supplies one backed by `AndroidSqliteDriver` (needs a `Context`),
 * desktop one backed by `JdbcSqliteDriver` (needs a file path) -- this
 * `commonMain` class deliberately doesn't know about either, so it stays
 * platform-agnostic. See `AndroidSqlDriverFactory` / `DesktopSqlDriverFactory`.
 */
class LibraryIndexRepository(
    driver: SqlDriver,
) {
    private val database = InkstaveDatabase(driver)

    /**
     * Inserts or replaces this score's index row from its manifest. Every
     * write path that changes a `.smpk`'s manifest -- import, metadata edit,
     * receiving one via sync -- must call this in the same operation, per
     * ADR-0005's "keep the index in sync" consequence.
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

    /** Every indexed score, alphabetical by title -- backs the library screen's default listing. */
    fun listAll(): List<ScoreSummary> =
        database.scoreIndexQueries
            .selectAll()
            .executeAsList()
            .map { it.toSummary() }

    /** Scores whose title or composer contains [query] (case-insensitive) -- backs library search. */
    fun search(query: String): List<ScoreSummary> =
        database.scoreIndexQueries
            .searchByTitleOrComposer(query)
            .executeAsList()
            .map { it.toSummary() }

    /**
     * Removes a score's index row (e.g. after its `.smpk` is deleted). Safe
     * by construction: the index is a rebuildable cache, never the only copy
     * of anything (ADR-0005).
     */
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
