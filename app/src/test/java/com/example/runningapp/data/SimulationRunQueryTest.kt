package com.example.runningapp.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A Simulation Run is kept and counts toward nothing (#560) — the reads that feed a count, put to a
 * real SQLite database held in memory.
 *
 * Each read is the very constant its `SessionDao` query is declared with, so the test runs the
 * question the phone asks rather than a hand-copied `WHERE` free to drift from it. Every test puts
 * a real Run and a Simulation Run with the same numbers side by side: the only thing between them is
 * the mark, so whatever comes back for one and not the other came back on the mark alone.
 */
class SimulationRunQueryTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.exec(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTime INTEGER NOT NULL,
                endTime INTEGER NOT NULL DEFAULT 0,
                durationSeconds INTEGER NOT NULL DEFAULT 0,
                movingTimeSeconds INTEGER,
                distanceKm REAL NOT NULL DEFAULT 0,
                effortScore INTEGER,
                isWalk INTEGER NOT NULL DEFAULT 0,
                ranAtUtcOffsetSeconds INTEGER,
                includeInAiTraining INTEGER NOT NULL DEFAULT 1,
                recordsScored INTEGER NOT NULL DEFAULT 0,
                segmentsTimed INTEGER NOT NULL DEFAULT 0,
                stageSettled INTEGER NOT NULL DEFAULT 0,
                isSimulationRun INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        // The same Run twice — one recorded for real, one with Simulation on.
        givenRun(id = 1, isSimulationRun = false)
        givenRun(id = 2, isSimulationRun = true)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `Fitness and Fatigue are built from the real Run only`() {
        assertEquals(listOf(100L), db.column(SCORED_RUNS_SQL, "startTime"))
    }

    @Test
    fun `the weekly totals are added up from the real Run only`() {
        assertEquals(listOf(100L), db.column(RUN_VOLUMES_SQL, "startTime"))
    }

    @Test
    fun `the record book is never asked to score a Simulation Run`() {
        assertEquals(listOf(1L), db.column(RUNS_OWING_RECORD_SCORING_SQL, "id"))
    }

    @Test
    fun `an unscored Simulation Run leaves no record scoring owed`() {
        db.exec("UPDATE sessions SET recordsScored = 1 WHERE id = 1")

        assertEquals(listOf(0L), db.column(ANY_RECORD_SCORING_OWED_SQL, null))
    }

    @Test
    fun `the Segments are never walked with a Simulation Run`() {
        assertEquals(listOf(1L), db.column(RUNS_OWING_SEGMENT_TIMING_SQL, "id"))
    }

    @Test
    fun `an untimed Simulation Run leaves no Segment timing owed`() {
        db.exec("UPDATE sessions SET segmentsTimed = 1 WHERE id = 1")

        assertEquals(listOf(0L), db.column(ANY_SEGMENT_TIMING_OWED_SQL, null))
    }

    @Test
    fun `a Simulation Run never owes its Stage a settlement`() {
        assertEquals(listOf(1L), db.column(RUNS_OWING_A_STAGE_SETTLEMENT, "id"))
    }

    @Test
    fun `a Simulation Run is never one the coach may be shown, even marked shareable`() {
        val sql = AI_ELIGIBLE_IDS_IN_SQL.replace(":sessionIds", "1, 2")

        assertEquals(listOf(1L), db.column(sql, "id"))
    }

    private fun givenRun(id: Long, isSimulationRun: Boolean) = db.exec(
        "INSERT INTO sessions (id, startTime, endTime, durationSeconds, movingTimeSeconds, " +
            "distanceKm, effortScore, ranAtUtcOffsetSeconds, isSimulationRun) VALUES " +
            "($id, ${if (isSimulationRun) 200 else 100}, 3000, 1800, 1700, 5.0, 60, 0, " +
            "${if (isSimulationRun) 1 else 0})"
    )

    /** One column of every row [sql] returns, or the first column where [name] is null. */
    private fun Connection.column(sql: String, name: String?): List<Long> =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildList { while (rows.next()) add(if (name == null) rows.getLong(1) else rows.getLong(name)) }
            }
        }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
