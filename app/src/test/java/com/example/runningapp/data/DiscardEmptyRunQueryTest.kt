package com.example.runningapp.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The statement that takes away the row of a Run that never recorded a second (#314), against a
 * real SQLite database held in memory.
 *
 * It is run here rather than checked through a fake DAO because what is being proved is not that
 * the repository asks the right question — it is that the question and the deletion are *one*
 * statement. The teardown that calls it has waited for every writer it knows of, but each of those
 * waits is bounded: a drain that gives up, or a fix from a looper asked to quit and never joined,
 * leaves a producer that can still commit. A read and then a delete would decide about a row that
 * can change in between, and the row it deleted would be a Run with its record inside it. Only the
 * statement itself can show there is no such in-between, and only a real engine can run it.
 *
 * The cascade is a promise the schema keeps rather than the code, so it is checked the same way.
 */
class DiscardEmptyRunQueryTest {

    private lateinit var db: Connection

    private val startedAt = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Room asks for this on every connection it opens, and the Pause case below is a cascade.
        db.exec("PRAGMA foreign_keys=ON")
        db.exec(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTime INTEGER NOT NULL,
                endTime INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        db.exec(
            """
            CREATE TABLE hr_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER NOT NULL,
                elapsedSeconds INTEGER NOT NULL,
                FOREIGN KEY (sessionId) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """
        )
        db.exec(
            """
            CREATE TABLE track_points (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER NOT NULL,
                timestampMillis INTEGER NOT NULL,
                FOREIGN KEY (sessionId) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """
        )
        db.exec(
            """
            CREATE TABLE run_pauses (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER NOT NULL,
                startTimeMillis INTEGER NOT NULL,
                FOREIGN KEY (sessionId) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the empty row of a Run that never recorded a second is taken away`() {
        givenRun(67L)

        assertEquals(1, discard(67L))
        assertEquals(0, rowsIn("sessions"))
    }

    @Test
    fun `a Run that banked one second is left exactly where it is`() {
        givenRun(67L)
        givenSample(67L)

        assertEquals(0, discard(67L))
        assertEquals(1, rowsIn("sessions"))
    }

    @Test
    fun `a Run that recorded only a fix is left exactly where it is`() {
        // One fix minutes after START proves both that the Run was recording and how far into it
        // that was — the same rule the rebuild reads it by.
        givenRun(67L)
        givenFix(67L)

        assertEquals(0, discard(67L))
        assertEquals(1, rowsIn("sessions"))
    }

    @Test
    fun `a Run somebody finished is left alone, however empty it looks`() {
        // The finalize that beat the teardown there. The row holds the Run's own totals, and totals
        // are not evidence of samples, so its emptiness elsewhere proves nothing.
        givenRun(67L, endTime = startedAt + 60_000)

        assertEquals(0, discard(67L))
        assertEquals(1, rowsIn("sessions"))
    }

    @Test
    fun `a Run that banked only a Pause goes, and the Pause goes with it`() {
        // A Pause says where the clock stopped; it does not say a second was written down. With no
        // sample and no fix the row can never be rebuilt, so it goes — and the database takes its
        // children itself.
        givenRun(67L)
        db.exec("INSERT INTO run_pauses (sessionId, startTimeMillis) VALUES (67, $startedAt)")

        assertEquals(1, discard(67L))
        assertEquals(0, rowsIn("run_pauses"))
    }

    @Test
    fun `a row that is not there at all is nothing to take away`() {
        assertEquals(0, discard(67L))
    }

    @Test
    fun `only the Run named is taken away`() {
        givenRun(67L)
        givenRun(68L)

        assertEquals(1, discard(67L))
        assertEquals(listOf(68L), db.query("SELECT id FROM sessions"))
    }

    /**
     * The statement the phone runs, run here — the same constant the DAO is annotated with, with
     * Room's named parameter swapped for JDBC's positional one.
     */
    private fun discard(sessionId: Long): Int =
        db.prepareStatement(DELETE_RUN_THAT_RECORDED_NOTHING.replace(":sessionId", "?")).use {
            it.setLong(1, sessionId)
            it.setLong(2, sessionId)
            it.setLong(3, sessionId)
            it.executeUpdate()
        }

    private fun givenRun(id: Long, endTime: Long = 0L) =
        db.exec("INSERT INTO sessions (id, startTime, endTime) VALUES ($id, $startedAt, $endTime)")

    private fun givenSample(sessionId: Long) =
        db.exec("INSERT INTO hr_samples (sessionId, elapsedSeconds) VALUES ($sessionId, 1)")

    private fun givenFix(sessionId: Long) = db.exec(
        "INSERT INTO track_points (sessionId, timestampMillis) VALUES ($sessionId, ${startedAt + 120_000})"
    )

    private fun rowsIn(table: String): Int = db.query("SELECT COUNT(*) FROM $table").single().toInt()

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.query(sql: String): List<Long> = createStatement().use { statement ->
        statement.executeQuery(sql).use {
            buildList { while (it.next()) add(it.getLong(1)) }
        }
    }
}
