package com.example.runningapp.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The query a suggested route distance is worked out from (#422) — `SessionDao.recentMeasuredRuns`
 * — against a real SQLite database held in memory.
 *
 * Every one of its conditions is there to keep a number out of the median that would move it without
 * being about the runner's pace, and the conditions *are* the whole of the rule: nothing downstream
 * re-checks any of them ([com.example.runningapp.ui.suggestedRouteDistanceMeters] takes the rows it
 * is handed as Runs that count). A fake DAO would only prove the repository called something, so the
 * predicates are pinned here against an engine that runs them.
 *
 * The window is the pair of bounds the caller cuts from one reading of its clock, and the far end is
 * the one with a story: a clock corrected backwards after a Run leaves that Run stamped later than
 * now — the same fact the plan's evaluation and the Progress curves already work around — and a
 * lower-bounded window would sort it as the newest Run there is and let it hold one of the counted
 * places until wall time caught up with it.
 */
class RecentMeasuredRunsQueryTest {

    private lateinit var db: Connection

    /** Ten in the morning, and the moment the read is taken. */
    private val now = 1_700_000_000_000L

    /** The statement as `SessionDao.recentMeasuredRuns` declares it, with the window bound in. */
    private val recentMeasuredRuns = """
        SELECT id AS sessionId, ranUnderStageId, ranUnderWorkoutId, durationSeconds, distanceKm
        FROM sessions
        WHERE endTime > 0
          AND startTime >= ${now - 90L * 24 * 60 * 60 * 1000}
          AND startTime <= $now
          AND runMode = 'outdoor'
          AND distanceKm > 0
          AND durationSeconds > 120
          AND isWalk = 0
        ORDER BY startTime DESC
    """

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
                distanceKm REAL NOT NULL DEFAULT 0,
                runMode TEXT NOT NULL DEFAULT 'outdoor',
                isWalk INTEGER NOT NULL DEFAULT 0,
                ranUnderStageId TEXT,
                ranUnderWorkoutId TEXT
            )
            """
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `every measured run in the window comes back, newest first`() {
        // Inserted oldest first, to prove the ordering is the query's and not the table's.
        givenRun(id = 1, startTime = now - 3 * DAY)
        givenRun(id = 2, startTime = now - 2 * DAY)
        givenRun(id = 3, startTime = now - DAY)

        assertEquals(listOf(3L, 2L, 1L), db.runIds())
    }

    /**
     * The finding this bound was added for: the device clock was corrected backwards after the Run,
     * so its `startTime` is later than the read's own clock. It is a Run that has not happened, and
     * left in it would sit at the head of the list for as long as it took wall time to catch up.
     */
    @Test
    fun `a run stamped after the read's own clock is not in the window`() {
        givenRun(id = 1, startTime = now - DAY)
        givenRun(id = 2, startTime = now + DAY)

        assertEquals(listOf(1L), db.runIds())
    }

    /** The far end is inclusive: a Run stamped in this very millisecond is not in the future. */
    @Test
    fun `a run stamped in the very millisecond of the read counts`() {
        givenRun(id = 1, startTime = now)

        assertEquals(listOf(1L), db.runIds())
    }

    @Test
    fun `a run older than the window has nothing to say about today's fitness`() {
        givenRun(id = 1, startTime = now - 89 * DAY)
        givenRun(id = 2, startTime = now - 91 * DAY)

        assertEquals(listOf(1L), db.runIds())
    }

    @Test
    fun `unfinished, treadmill, unmeasured, abandoned and walked runs are all left out`() {
        givenRun(id = 1, startTime = now - DAY)
        givenRun(id = 2, startTime = now - 2 * DAY, endTime = 0)
        givenRun(id = 3, startTime = now - 3 * DAY, runMode = "treadmill")
        givenRun(id = 4, startTime = now - 4 * DAY, distanceKm = 0.0)
        givenRun(id = 5, startTime = now - 5 * DAY, durationSeconds = 120)
        givenRun(id = 6, startTime = now - 6 * DAY, isWalk = true)

        assertEquals(listOf(1L), db.runIds())
    }

    private fun givenRun(
        id: Long,
        startTime: Long,
        endTime: Long = startTime + 1_800_000L,
        durationSeconds: Long = 1_800,
        distanceKm: Double = 5.0,
        runMode: String = "outdoor",
        isWalk: Boolean = false,
    ) = db.exec(
        """
        INSERT INTO sessions
            (id, startTime, endTime, durationSeconds, distanceKm, runMode, isWalk)
        VALUES
            ($id, $startTime, $endTime, $durationSeconds, $distanceKm, '$runMode',
             ${if (isWalk) 1 else 0})
        """
    )

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    /** The ids the query returns, in the order it returns them. */
    private fun Connection.runIds(): List<Long> = createStatement().use { statement ->
        statement.executeQuery(recentMeasuredRuns).use {
            buildList { while (it.next()) add(it.getLong(1)) }
        }
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
