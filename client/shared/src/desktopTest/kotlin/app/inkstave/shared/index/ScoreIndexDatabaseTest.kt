package app.inkstave.shared.index

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves the SQLDelight-generated local index (ADR-0005,
 * `docs/decisions/0005-local-library-index-database.md`) actually works:
 * a row written through [InkstaveDatabase] can be read back, searched, and
 * deleted. This is the "integration test" `docs/testing-strategy.md`
 * requires for the local index database's central claim.
 *
 * Runs against an in-memory SQLite database via the JDBC driver -- the same
 * driver the real desktop app uses (`docs/decisions/0001-client-framework.md`),
 * so this exercises real SQL, not a fake/in-process substitute.
 */
class ScoreIndexDatabaseTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: InkstaveDatabase

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        InkstaveDatabase.Schema.create(driver)
        database = InkstaveDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `upsert then selectById returns the row`() {
        database.scoreIndexQueries.upsert(
            id = "score-1",
            filePath = "/library/clair-de-lune.smpk",
            title = "Clair de Lune",
            subtitle = null,
            composer = "Claude Debussy",
            arranger = null,
            lyricist = null,
            genre = "Classical",
            tagsCsv = "piano,impressionist",
            createdAt = "2026-09-23T00:00:00Z",
            modifiedAt = "2026-09-23T00:00:00Z",
            indexedAt = "2026-09-23T00:00:00Z",
        )

        val row = database.scoreIndexQueries.selectById("score-1").executeAsOne()

        assertEquals("Clair de Lune", row.title)
        assertEquals("Claude Debussy", row.composer)
    }

    @Test
    fun `searchByTitleOrComposer matches on composer`() {
        database.scoreIndexQueries.upsert(
            id = "score-1",
            filePath = "/library/clair-de-lune.smpk",
            title = "Clair de Lune",
            subtitle = null,
            composer = "Claude Debussy",
            arranger = null,
            lyricist = null,
            genre = "Classical",
            tagsCsv = "piano",
            createdAt = "2026-09-23T00:00:00Z",
            modifiedAt = "2026-09-23T00:00:00Z",
            indexedAt = "2026-09-23T00:00:00Z",
        )

        val results = database.scoreIndexQueries.searchByTitleOrComposer("debussy").executeAsList()

        assertEquals(1, results.size)
        assertEquals("score-1", results.first().id)
    }

    @Test
    fun `deleteById removes the row, proving the index is safely rebuildable`() {
        database.scoreIndexQueries.upsert(
            id = "score-1",
            filePath = "/library/clair-de-lune.smpk",
            title = "Clair de Lune",
            subtitle = null,
            composer = null,
            arranger = null,
            lyricist = null,
            genre = null,
            tagsCsv = "",
            createdAt = "2026-09-23T00:00:00Z",
            modifiedAt = "2026-09-23T00:00:00Z",
            indexedAt = "2026-09-23T00:00:00Z",
        )

        database.scoreIndexQueries.deleteById("score-1")

        assertNull(database.scoreIndexQueries.selectById("score-1").executeAsOneOrNull())
    }
}
