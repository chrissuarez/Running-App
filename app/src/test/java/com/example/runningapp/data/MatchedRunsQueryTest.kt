package com.example.runningapp.data

import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.ui.matchedRunsUi
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A Run's matched-runs card end to end, against a real SQLite database held in memory (#73).
 *
 * The grouping itself is pinned next door ([com.example.runningapp.ui.MatchedRunModelsTest]); what
 * can only be shown here is that the read hands that grouping every shaped Run there is, and that
 * **throwing a Run away takes it out of the group** — which is a promise the schema keeps
 * ([RunShapeRow]'s cascade) rather than the code, and so cannot be checked against a fake DAO at all.
 */
class MatchedRunsQueryTest {

    private lateinit var db: Connection

    private val zone = ZoneId.of("Europe/London")
    private val firstMorning = 1_700_000_000_000L
    private val aDay = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // The cascade is the whole point of the delete case, and SQLite ignores foreign keys unless
        // it is asked not to — as Room asks on every connection it opens.
        db.exec("PRAGMA foreign_keys=ON")
        db.exec(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTime INTEGER NOT NULL,
                ranAtUtcOffsetSeconds INTEGER,
                durationSeconds INTEGER NOT NULL,
                movingTimeSeconds INTEGER,
                avgPaceMinPerKm REAL NOT NULL
            )
            """
        )
        db.exec(
            """
            CREATE TABLE run_shapes (
                sessionId INTEGER PRIMARY KEY NOT NULL,
                shape TEXT,
                distanceMeters REAL NOT NULL,
                FOREIGN KEY (sessionId) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `every shaped run reaches the grouping, so a run knows where it stands`() {
        givenRunOverTheBlock(id = 1, day = 0)
        givenRunOverTheBlock(id = 2, day = 7)
        givenRunOverTheBlock(id = 3, day = 14)

        val group = groupFor(sessionId = 3)!!

        assertEquals(listOf(1L, 2L, 3L), group.runs.map { it.sessionId })
        assertEquals(3, group.position)
    }

    @Test
    fun `deleting a run takes it out of the group, and the count with it`() {
        givenRunOverTheBlock(id = 1, day = 0)
        givenRunOverTheBlock(id = 2, day = 7)
        givenRunOverTheBlock(id = 3, day = 14)
        assertEquals(3, groupFor(sessionId = 1)!!.count)

        db.exec("DELETE FROM sessions WHERE id = 2")

        val group = groupFor(sessionId = 1)!!
        assertEquals(listOf(1L, 3L), group.runs.map { it.sessionId })
        assertEquals(2, group.count)
    }

    @Test
    fun `deleting the run being looked at leaves no group at all`() {
        givenRunOverTheBlock(id = 1, day = 0)
        givenRunOverTheBlock(id = 2, day = 7)

        db.exec("DELETE FROM sessions WHERE id = 1")

        assertNull(groupFor(sessionId = 1))
    }

    @Test
    fun `a run written down as holding no shape is in nobody's group`() {
        givenRunOverTheBlock(id = 1, day = 0)
        givenRunOverTheBlock(id = 2, day = 7)
        // What a Walk's row looks like: measured, and holding nothing (#73).
        db.exec(
            "INSERT INTO sessions (id, startTime, ranAtUtcOffsetSeconds, durationSeconds, " +
                "movingTimeSeconds, avgPaceMinPerKm) VALUES (3, ${firstMorning + 14 * aDay}, 0, 1800, 1700, 6.0)"
        )
        db.exec("INSERT INTO run_shapes (sessionId, shape, distanceMeters) VALUES (3, NULL, 0.0)")

        assertEquals(listOf(1L, 2L), groupFor(sessionId = 1)!!.runs.map { it.sessionId })
        assertNull(groupFor(sessionId = 3))
    }

    /** A kilometre round the block, written down the way the shaping pass writes one. */
    private fun givenRunOverTheBlock(id: Long, day: Long) {
        db.exec(
            "INSERT INTO sessions (id, startTime, ranAtUtcOffsetSeconds, durationSeconds, " +
                "movingTimeSeconds, avgPaceMinPerKm) " +
                "VALUES ($id, ${firstMorning + day * aDay}, 0, 1800, 1700, 6.0)"
        )
        val shape = RoutePolyline.encode(
            listOf(
                RoutePoint(51.5000, -0.1000, null),
                RoutePoint(51.5000, -0.0964, null),
                RoutePoint(51.5022, -0.0964, null),
                RoutePoint(51.5022, -0.1000, null),
                RoutePoint(51.5000, -0.1000, null),
            )
        )
        db.exec("INSERT INTO run_shapes (sessionId, shape, distanceMeters) VALUES ($id, '$shape', 1000.0)")
    }

    private fun groupFor(sessionId: Long) = matchedRunsUi(shapedRuns(), sessionId, zone)

    /** The DAO's own read ([SHAPED_RUNS_SQL]), run against the real table. */
    private fun shapedRuns(): List<RunShapeCandidate> {
        val rows = mutableListOf<RunShapeCandidate>()
        db.createStatement().use { statement ->
            statement.executeQuery(SHAPED_RUNS_SQL).use { cursor ->
                while (cursor.next()) {
                    rows += RunShapeCandidate(
                        sessionId = cursor.getLong("sessionId"),
                        shape = cursor.getString("shape"),
                        distanceMeters = cursor.getDouble("distanceMeters"),
                        startTime = cursor.getLong("startTime"),
                        ranAtUtcOffsetSeconds = cursor.getInt("ranAtUtcOffsetSeconds"),
                        durationSeconds = cursor.getLong("durationSeconds"),
                        movingTimeSeconds = cursor.getLong("movingTimeSeconds"),
                        avgPaceMinPerKm = cursor.getDouble("avgPaceMinPerKm"),
                    )
                }
            }
        }
        return rows
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
