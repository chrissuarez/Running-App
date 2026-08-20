package com.example.runningapp.data

import com.example.runningapp.analysis.Medal
import com.example.runningapp.ui.runSegmentEffortsUi
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Run's Segments card end to end, against a real SQLite database held in memory (#71).
 *
 * The pure ranking is pinned next door
 * ([com.example.runningapp.ui.RunSegmentEffortsTest]); what can only be shown here is that the read
 * actually hands that ranking the rows it needs — the Run's own efforts *and* every rival's — and
 * that throwing a Run away moves the medals, which is a promise the schema keeps rather than the
 * code ([SegmentEffort]'s cascade), and so cannot be checked against a fake DAO at all.
 */
class RunSegmentEffortsQueryTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // The cascade is the whole point of the delete case, and SQLite ignores foreign keys unless
        // it is asked not to — as Room asks on every connection it opens.
        db.exec("PRAGMA foreign_keys=ON")
        db.exec("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        db.exec(
            """
            CREATE TABLE segments (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                polyline TEXT NOT NULL,
                distanceMeters REAL NOT NULL,
                sourceSessionId INTEGER,
                createdAtMillis INTEGER NOT NULL,
                historyTimed INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (sourceSessionId) REFERENCES sessions(id) ON DELETE SET NULL
            )
            """
        )
        db.exec(
            """
            CREATE TABLE segment_efforts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                segmentId INTEGER NOT NULL,
                sessionId INTEGER NOT NULL,
                startedAtMillis INTEGER NOT NULL,
                finishedAtMillis INTEGER NOT NULL,
                FOREIGN KEY (segmentId) REFERENCES segments(id) ON DELETE CASCADE,
                FOREIGN KEY (sessionId) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """
        )
        // The schema's own word for "already scanned" ([SegmentEffort]) — carried here so a staging
        // step that quietly wrote the same crossing twice would fail rather than double a rival.
        db.exec(
            "CREATE UNIQUE INDEX index_segment_efforts_segmentId_sessionId_startedAtMillis " +
                "ON segment_efforts (segmentId, sessionId, startedAtMillis)"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a run is placed against every effort at the segments it went over`() {
        givenRuns(1, 2, 3)
        givenSegment(id = 1, name = "Cemetery Hill", distanceMeters = 500.0)
        givenEffort(segmentId = 1, sessionId = 1, startedAtMillis = 1_000, elapsedMillis = 200_000)
        givenEffort(segmentId = 1, sessionId = 2, startedAtMillis = 2_000, elapsedMillis = 190_000)
        givenEffort(segmentId = 1, sessionId = 3, startedAtMillis = 3_000, elapsedMillis = 180_000)

        val card = cardFor(sessionId = 1)

        assertEquals(listOf("Cemetery Hill"), card.map { it.segmentName })
        // Third of three: the read carried the two rivals, or this would have read as the record.
        assertEquals(Medal.BRONZE, card.single().medal)
    }

    @Test
    fun `a run that went over nothing reads back nothing`() {
        givenRuns(1, 2)
        givenSegment(id = 1, name = "Cemetery Hill", distanceMeters = 500.0)
        givenEffort(segmentId = 1, sessionId = 2, startedAtMillis = 1_000, elapsedMillis = 200_000)

        assertTrue(cardFor(sessionId = 1).isEmpty())
    }

    @Test
    fun `segments this run never crossed are left out of its card entirely`() {
        givenRuns(1, 2)
        givenSegment(id = 1, name = "Cemetery Hill", distanceMeters = 500.0)
        givenSegment(id = 2, name = "The Straight", distanceMeters = 800.0)
        givenEffort(segmentId = 1, sessionId = 1, startedAtMillis = 1_000, elapsedMillis = 200_000)
        givenEffort(segmentId = 2, sessionId = 2, startedAtMillis = 2_000, elapsedMillis = 300_000)

        assertEquals(listOf("Cemetery Hill"), cardFor(sessionId = 1).map { it.segmentName })
    }

    @Test
    fun `deleting a run takes its efforts with it and hands the medal on`() {
        givenRuns(1, 2)
        givenSegment(id = 1, name = "Cemetery Hill", distanceMeters = 500.0)
        givenEffort(segmentId = 1, sessionId = 1, startedAtMillis = 1_000, elapsedMillis = 200_000)
        givenEffort(segmentId = 1, sessionId = 2, startedAtMillis = 2_000, elapsedMillis = 150_000)
        assertEquals(Medal.SILVER, cardFor(sessionId = 1).single().medal)

        db.exec("DELETE FROM sessions WHERE id = 2")

        // The quicker run is gone, so its ghost holds no record: the slower one is now the best
        // anybody has ever run up the hill.
        assertTrue(cardFor(sessionId = 2).isEmpty())
        assertEquals(Medal.GOLD, cardFor(sessionId = 1).single().medal)
    }

    @Test
    fun `deleting a run that held the record leaves the rest of the top three moved up`() {
        givenRuns(1, 2, 3, 4)
        givenSegment(id = 1, name = "Cemetery Hill", distanceMeters = 500.0)
        givenEffort(segmentId = 1, sessionId = 1, startedAtMillis = 1_000, elapsedMillis = 100_000)
        givenEffort(segmentId = 1, sessionId = 2, startedAtMillis = 2_000, elapsedMillis = 110_000)
        givenEffort(segmentId = 1, sessionId = 3, startedAtMillis = 3_000, elapsedMillis = 120_000)
        givenEffort(segmentId = 1, sessionId = 4, startedAtMillis = 4_000, elapsedMillis = 130_000)
        assertNull(cardFor(sessionId = 4).single().medal)

        db.exec("DELETE FROM sessions WHERE id = 1")

        assertEquals(Medal.GOLD, cardFor(sessionId = 2).single().medal)
        assertEquals(Medal.SILVER, cardFor(sessionId = 3).single().medal)
        assertEquals(Medal.BRONZE, cardFor(sessionId = 4).single().medal)
    }

    /** The card as the screen would draw it: the app's own read, then the app's own ranking. */
    private fun cardFor(sessionId: Long) = runSegmentEffortsUi(rowsFor(sessionId), sessionId)

    /**
     * [SegmentEffortDao.getEffortsForRunFlow]'s own SQL, over JDBC.
     *
     * The named parameter is Room's way of writing the one thing JDBC spells `?`; nothing else about
     * the statement is touched, which is what makes this the app's read rather than a copy of it.
     */
    private fun rowsFor(sessionId: Long): List<RunSegmentEffortRow> {
        val statement = db.prepareStatement(RUN_SEGMENT_EFFORTS_SQL.replace(":sessionId", "?"))
        statement.setLong(1, sessionId)
        val rows = mutableListOf<RunSegmentEffortRow>()
        statement.executeQuery().use { results ->
            while (results.next()) {
                rows += RunSegmentEffortRow(
                    effortId = results.getLong("effortId"),
                    segmentId = results.getLong("segmentId"),
                    segmentName = results.getString("segmentName"),
                    distanceMeters = results.getDouble("distanceMeters"),
                    sessionId = results.getLong("sessionId"),
                    startedAtMillis = results.getLong("startedAtMillis"),
                    elapsedMillis = results.getLong("elapsedMillis"),
                )
            }
        }
        statement.close()
        return rows
    }

    private fun givenRuns(vararg ids: Long) {
        ids.forEach { db.exec("INSERT INTO sessions (id) VALUES ($it)") }
    }

    private fun givenSegment(id: Long, name: String, distanceMeters: Double) {
        db.exec(
            "INSERT INTO segments (id, name, polyline, distanceMeters, sourceSessionId, " +
                "createdAtMillis, historyTimed) VALUES ($id, '$name', '', $distanceMeters, NULL, 0, 1)"
        )
    }

    private fun givenEffort(segmentId: Long, sessionId: Long, startedAtMillis: Long, elapsedMillis: Long) {
        db.exec(
            "INSERT INTO segment_efforts (segmentId, sessionId, startedAtMillis, finishedAtMillis) " +
                "VALUES ($segmentId, $sessionId, $startedAtMillis, ${startedAtMillis + elapsedMillis})"
        )
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
