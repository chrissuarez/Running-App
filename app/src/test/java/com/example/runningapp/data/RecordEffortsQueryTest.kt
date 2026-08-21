package com.example.runningapp.data

import com.example.runningapp.analysis.RecordType
import com.example.runningapp.ui.recordSlots
import com.example.runningapp.ui.recordTopEfforts
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Records section end to end, against a real SQLite database held in memory (#75).
 *
 * The placing and the wording are pinned next door
 * ([com.example.runningapp.ui.RecordsModelsTest]); what can only be shown here is that the read
 * hands that placing every claim there is, and that **throwing a Run away takes its claims off the
 * book** — which is a promise the schema keeps ([RunEffortRow]'s cascade) rather than the code, and
 * so cannot be checked against a fake DAO at all. That promise is what stops a deleted Run holding
 * fourth place for ever: nothing mends the top ten, because nothing has to.
 */
class RecordEffortsQueryTest {

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
                ranAtUtcOffsetSeconds INTEGER
            )
            """
        )
        db.exec(
            """
            CREATE TABLE run_efforts (
                sessionId INTEGER NOT NULL,
                type TEXT NOT NULL,
                value REAL NOT NULL,
                PRIMARY KEY (sessionId, type),
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
    fun `every claim ever banked reaches the placing, however deep it sits`() {
        (1L..12L).forEach { givenRun(it, day = it, fiveKSeconds = 1_500.0 + it) }

        val top = recordTopEfforts(effortRows(), RecordType.FASTEST_5K, zone)

        assertEquals(10, top.size)
        assertEquals(listOf(1L, 2L, 3L), top.take(3).map { it.effort.sessionId })
        // Tenth place is a Run the record book never remembered — three deep, it holds nothing.
        assertEquals(10L, top.last().effort.sessionId)
    }

    @Test
    fun `deleting a run takes its claims with it, and everyone below moves up`() {
        (1L..11L).forEach { givenRun(it, day = it, fiveKSeconds = 1_500.0 + it) }
        assertEquals(1L, recordSlots(effortRows(), zone).single { it.type == RecordType.FASTEST_5K }.best?.sessionId)

        db.exec("DELETE FROM sessions WHERE id = 1")

        val slots = recordSlots(effortRows(), zone)
        val fiveK = slots.single { it.type == RecordType.FASTEST_5K }
        assertEquals(2L, fiveK.best?.sessionId)
        assertEquals(10, recordTopEfforts(effortRows(), RecordType.FASTEST_5K, zone).size)
        // The eleventh Run was outside the ten and is inside it now, with nothing having mended it.
        assertEquals(11L, recordTopEfforts(effortRows(), RecordType.FASTEST_5K, zone).last().effort.sessionId)
    }

    @Test
    fun `a record nobody has ever contested has no rows and stands empty`() {
        givenRun(1L, day = 1, fiveKSeconds = 1_500.0)

        val tenK = recordSlots(effortRows(), zone).single { it.type == RecordType.FASTEST_10K }

        assertNull(tenK.best)
        assertTrue(recordTopEfforts(effortRows(), RecordType.FASTEST_10K, zone).isEmpty())
    }

    @Test
    fun `re-measuring a run replaces its claim rather than standing beside it`() {
        givenRun(1L, day = 1, fiveKSeconds = 1_500.0)

        // What the banking does when a Run is scored again — the same key, a new value.
        db.exec(
            "INSERT OR REPLACE INTO run_efforts (sessionId, type, value) " +
                "VALUES (1, 'FASTEST_5K', 1400.0)"
        )

        assertEquals(1, recordTopEfforts(effortRows(), RecordType.FASTEST_5K, zone).size)
        assertEquals(
            "23:20",
            recordSlots(effortRows(), zone).single { it.type == RecordType.FASTEST_5K }.best?.valueLabel,
        )
    }

    private fun givenRun(id: Long, day: Long, fiveKSeconds: Double) {
        db.exec(
            "INSERT INTO sessions (id, startTime, ranAtUtcOffsetSeconds) " +
                "VALUES ($id, ${firstMorning + day * aDay}, 0)"
        )
        db.exec(
            "INSERT INTO run_efforts (sessionId, type, value) VALUES ($id, 'FASTEST_5K', $fiveKSeconds)"
        )
    }

    /** The DAO's own read ([RECORD_EFFORTS_SQL]), run against the real table. */
    private fun effortRows(): List<RecordEffortRow> {
        val rows = mutableListOf<RecordEffortRow>()
        db.createStatement().use { statement ->
            statement.executeQuery(RECORD_EFFORTS_SQL).use { cursor ->
                while (cursor.next()) {
                    rows += RecordEffortRow(
                        sessionId = cursor.getLong("sessionId"),
                        type = RecordType.valueOf(cursor.getString("type")),
                        value = cursor.getDouble("value"),
                        startTime = cursor.getLong("startTime"),
                        ranAtUtcOffsetSeconds = cursor.getInt("ranAtUtcOffsetSeconds"),
                    )
                }
            }
        }
        return rows
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
