package com.example.runningapp.data

import com.example.runningapp.analysis.RecordType
import com.example.runningapp.ui.recordSlots
import com.example.runningapp.ui.recordTopEfforts
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *
 * And the other thing only a real database can show (#346): the fill flag and the claims come back
 * from **one statement**, so no reading can pair a flag lowered by one write with claims read before
 * the write that finished filling them. Every read below goes through that one statement
 * ([RECORD_BOOK_SQL]) and the fold the repository makes of it ([recordBookOf]).
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
        db.exec(RECORD_FILL_TABLE_SQL)
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

    @Test
    fun `a raised fill flag comes back with no claims, however many the table already holds`() {
        // The first launch after the upgrade, part-way through: two Runs of a long history measured.
        givenRun(1L, day = 1, fiveKSeconds = 1_800.0)
        givenRun(2L, day = 2, fiveKSeconds = 1_700.0)
        givenFillOwed(true)

        val book = recordBook()

        assertTrue(book.measuring)
        // Not the two claims the pass has reached — a slice of history is never handed to a reader.
        assertEquals(emptyList<RecordEffortRow>(), book.efforts)
    }

    @Test
    fun `the read that sees the flag lowered sees every claim the fill wrote before it`() {
        givenFillOwed(true)
        givenRun(1L, day = 1, fiveKSeconds = 1_800.0)
        // The pass goes on filling, then hands the fill back in a write of its own (#346).
        givenRun(2L, day = 2, fiveKSeconds = 1_500.0)
        givenRun(3L, day = 3, fiveKSeconds = 1_700.0)
        givenFillOwed(false)

        val book = recordBook()

        assertFalse(book.measuring)
        assertEquals(listOf(1L, 2L, 3L), book.efforts.map { it.sessionId })
        assertEquals(2L, recordSlots(book.efforts, zone).single { it.type == RecordType.FASTEST_5K }.best?.sessionId)
    }

    @Test
    fun `a history with nothing banked and nothing owed is an answer of its own`() {
        // No rows at all to join, and still one answer: nothing is being measured and nothing stands.
        val book = recordBook()

        assertFalse(book.measuring)
        assertEquals(emptyList<RecordEffortRow>(), book.efforts)
    }

    @Test
    fun `a fresh install that never held the fill row reads as nothing owed`() {
        givenRun(1L, day = 1, fiveKSeconds = 1_500.0)

        val book = recordBook()

        assertFalse(book.measuring)
        assertEquals(1, book.efforts.size)
    }

    private fun givenFillOwed(owed: Boolean) {
        db.exec(
            "INSERT OR REPLACE INTO record_fill (id, wholesaleFillOwed) VALUES (0, ${if (owed) 1 else 0})"
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

    /** The claims of a reading taken when nothing is being measured — every test above sets none. */
    private fun effortRows(): List<RecordEffortRow> = recordBook().efforts

    /** The DAO's own read ([RECORD_BOOK_SQL]), run against the real tables and folded as the repository folds it. */
    private fun recordBook(): RecordBook {
        val rows = mutableListOf<RecordBookRow>()
        db.createStatement().use { statement ->
            statement.executeQuery(RECORD_BOOK_SQL).use { cursor ->
                while (cursor.next()) {
                    val sessionId = cursor.getLong("sessionId")
                    val effort = if (cursor.wasNull()) {
                        null
                    } else {
                        RecordEffortRow(
                            sessionId = sessionId,
                            type = RecordType.valueOf(cursor.getString("type")),
                            value = cursor.getDouble("value"),
                            startTime = cursor.getLong("startTime"),
                            ranAtUtcOffsetSeconds = cursor.getInt("ranAtUtcOffsetSeconds"),
                        )
                    }
                    rows += RecordBookRow(fillOwed = cursor.getBoolean("fillOwed"), effort = effort)
                }
            }
        }
        return recordBookOf(rows)
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
