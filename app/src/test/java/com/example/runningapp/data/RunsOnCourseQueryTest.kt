package com.example.runningapp.data

import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.routes.routeShapeOf
import com.example.runningapp.ui.runsOnCourse
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A saved course claiming the Runs that covered its ground, against a real SQLite database held in
 * memory (#74).
 *
 * The recognising itself is pinned next door ([com.example.runningapp.routes.RouteRunLinkTest]); what
 * can only be shown here are the two halves of the ticket that are promises about *rows* rather than
 * about geometry — **a course saved today claims the Runs that were already there**, and **a course
 * deleted stops claiming them without taking a single Run with it**, which is the schema's own
 * cascade ([RouteShapeRow]) and cannot be asked of a fake DAO at all.
 */
class RunsOnCourseQueryTest {

    private lateinit var db: Connection

    private val firstMorning = 1_700_000_000_000L
    private val aDay = 24 * 60 * 60 * 1000L

    /** A kilometre round the block, as a file would draw it: the four corners and back to the first. */
    private val theBlock = listOf(
        RoutePoint(51.5000, -0.1000, null),
        RoutePoint(51.5000, -0.0964, null),
        RoutePoint(51.5022, -0.0964, null),
        RoutePoint(51.5022, -0.1000, null),
        RoutePoint(51.5000, -0.1000, null),
    )

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
                endTime INTEGER NOT NULL,
                ranAtUtcOffsetSeconds INTEGER,
                durationSeconds INTEGER NOT NULL,
                movingTimeSeconds INTEGER,
                distanceKm REAL NOT NULL,
                isWalk INTEGER NOT NULL
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
        db.exec(
            """
            CREATE TABLE routes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL
            )
            """
        )
        // The upgrade's own words, so this is the table the app will really have.
        db.exec(CREATE_ROUTE_SHAPES_SQL)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a course saved today claims the runs that were already there`() {
        givenRunOverTheBlock(id = 1, day = 0)
        givenRunOverTheBlock(id = 2, day = 7)
        givenRunOverTheBlock(id = 3, day = 14)

        givenCourseOverTheBlock(id = 5, name = "Round the block")

        assertEquals(listOf(3L, 2L, 1L), runsOnThisCourse(routeId = 5).map { it.sessionId })
    }

    @Test
    fun `a run somewhere else is not claimed`() {
        givenRunOverTheBlock(id = 1, day = 0)
        givenRun(id = 2, day = 7, shape = RoutePolyline.encode(theNextBlockOver()), distanceMeters = 1000.0)

        givenCourseOverTheBlock(id = 5, name = "Round the block")

        assertEquals(listOf(1L), runsOnThisCourse(routeId = 5).map { it.sessionId })
    }

    @Test
    fun `deleting the course unlinks the runs and deletes none of them`() {
        givenRunOverTheBlock(id = 1, day = 0)
        givenRunOverTheBlock(id = 2, day = 7)
        givenCourseOverTheBlock(id = 5, name = "Round the block")
        assertEquals(2, runsOnThisCourse(routeId = 5).size)

        db.exec("DELETE FROM routes WHERE id = 5")

        // The shape went with the course, so nothing recognises anything on it any more...
        assertNull(courseShape(routeId = 5))
        assertTrue(runsOnThisCourse(routeId = 5).isEmpty())
        // ...and both Runs, and the shapes their own group is worked out from, are exactly as they
        // were. Nothing in the library points at history (ADR 0014), and this is that promise kept
        // on the one column that now names a course in both directions.
        assertEquals(2, countOf("SELECT COUNT(*) FROM sessions"))
        assertEquals(2, countOf("SELECT COUNT(*) FROM run_shapes"))
    }

    @Test
    fun `a run remembered on the course and recognised on it is listed once`() {
        givenRunOverTheBlock(id = 1, day = 0)
        givenCourseOverTheBlock(id = 5, name = "Round the block")

        val remembered = listOf(rememberedRow(sessionId = 1))
        val listed = runsOnCourse(remembered, shapedRuns(), courseShape(routeId = 5))

        assertEquals(listOf(1L), listed.map { it.sessionId })
    }

    @Test
    fun `a run remembered on the course but never shaped is still listed`() {
        // A Walk holds no shape at all, and the runner still went round this course on it (#420).
        db.exec(
            "INSERT INTO sessions (id, startTime, endTime, ranAtUtcOffsetSeconds, durationSeconds, " +
                "movingTimeSeconds, distanceKm, isWalk) " +
                "VALUES (1, $firstMorning, ${firstMorning + 1800_000}, 0, 1800, 1700, 1.0, 1)"
        )
        db.exec("INSERT INTO run_shapes (sessionId, shape, distanceMeters) VALUES (1, NULL, 0.0)")
        givenCourseOverTheBlock(id = 5, name = "Round the block")

        val listed = runsOnCourse(listOf(rememberedRow(sessionId = 1)), shapedRuns(), courseShape(routeId = 5))

        assertEquals(listOf(1L), listed.map { it.sessionId })
    }

    @Test
    fun `a course still owed its shape claims nothing and keeps its remembered runs`() {
        givenRunOverTheBlock(id = 1, day = 0)
        givenRunOverTheBlock(id = 2, day = 7)
        db.exec("INSERT INTO routes (id, name) VALUES (5, 'Round the block')")

        val listed = runsOnCourse(listOf(rememberedRow(sessionId = 2)), shapedRuns(), courseShape(routeId = 5))

        assertEquals(listOf(2L), listed.map { it.sessionId })
    }

    // -- Writing the rows ---------------------------------------------------------------------

    private fun givenRunOverTheBlock(id: Long, day: Long) =
        givenRun(id, day, RoutePolyline.encode(theBlock), distanceMeters = 1000.0)

    private fun givenRun(id: Long, day: Long, shape: String, distanceMeters: Double) {
        val start = firstMorning + day * aDay
        db.exec(
            "INSERT INTO sessions (id, startTime, endTime, ranAtUtcOffsetSeconds, durationSeconds, " +
                "movingTimeSeconds, distanceKm, isWalk) " +
                "VALUES ($id, $start, ${start + 1800_000}, 0, 1800, 1700, ${distanceMeters / 1000.0}, 0)"
        )
        db.exec("INSERT INTO run_shapes (sessionId, shape, distanceMeters) VALUES ($id, '$shape', $distanceMeters)")
    }

    /** A course kept over the block, measured exactly as keeping one measures it. */
    private fun givenCourseOverTheBlock(id: Long, name: String) {
        db.exec("INSERT INTO routes (id, name) VALUES ($id, '$name')")
        val row = routeShapeRowOf(id, routeShapeOf(theBlock))
        db.exec(
            "INSERT INTO route_shapes (routeId, shape, distanceMeters) " +
                "VALUES ($id, '${row.shape}', ${row.distanceMeters})"
        )
    }

    /** The same block a street further out — a different route of nearly the same length. */
    private fun theNextBlockOver(): List<RoutePoint> =
        theBlock.map { RoutePoint(it.latitude, it.longitude + 0.006, null) }

    /** What the remembered read returns for a Run — the other half of a course's list (#420). */
    private fun rememberedRow(sessionId: Long) = RouteRunRow(
        sessionId = sessionId,
        startTime = firstMorning,
        ranAtUtcOffsetSeconds = 0,
        durationSeconds = 1800,
        movingTimeSeconds = 1700,
        distanceKm = 1.0,
        isWalk = false,
    )

    // -- Reading them back, through the app's own queries --------------------------------------

    private fun runsOnThisCourse(routeId: Long) =
        runsOnCourse(remembered = emptyList(), shaped = shapedRuns(), course = courseShape(routeId))

    /** The DAO's own read ([SHAPED_RUNS_ON_COURSES_SQL]), run against the real tables. */
    private fun shapedRuns(): List<ShapedRunRow> {
        val rows = mutableListOf<ShapedRunRow>()
        db.createStatement().use { statement ->
            statement.executeQuery(SHAPED_RUNS_ON_COURSES_SQL).use { cursor ->
                while (cursor.next()) {
                    rows += ShapedRunRow(
                        run = RouteRunRow(
                            sessionId = cursor.getLong("sessionId"),
                            startTime = cursor.getLong("startTime"),
                            ranAtUtcOffsetSeconds = cursor.getInt("ranAtUtcOffsetSeconds"),
                            durationSeconds = cursor.getLong("durationSeconds"),
                            movingTimeSeconds = cursor.getLong("movingTimeSeconds"),
                            distanceKm = cursor.getDouble("distanceKm"),
                            isWalk = cursor.getBoolean("isWalk"),
                        ),
                        shape = cursor.getString("shape"),
                        shapeDistanceMeters = cursor.getDouble("shapeDistanceMeters"),
                    )
                }
            }
        }
        return rows
    }

    /** The DAO's own read ([ONE_COURSE_SHAPE_SQL]), run against the real tables. */
    private fun courseShape(routeId: Long) = db.createStatement().use { statement ->
        statement.executeQuery(ONE_COURSE_SHAPE_SQL.replace(":routeId", "$routeId")).use { cursor ->
            if (!cursor.next()) {
                null
            } else {
                RouteShapeCandidate(
                    routeId = cursor.getLong("routeId"),
                    name = cursor.getString("name"),
                    shape = cursor.getString("shape"),
                    distanceMeters = cursor.getDouble("distanceMeters"),
                ).decoded()
            }
        }
    }

    private fun countOf(sql: String) = db.createStatement().use { statement ->
        statement.executeQuery(sql).use { cursor ->
            cursor.next()
            cursor.getInt(1)
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
