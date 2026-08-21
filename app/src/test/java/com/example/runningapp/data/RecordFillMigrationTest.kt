package com.example.runningapp.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the v36 to v37 upgrade writes down about the fill it starts, against a real SQLite database
 * held in memory (#75).
 *
 * The upgrade creates `run_efforts` empty and clears every Run's scoring mark, so the whole of
 * history is re-measured a Run at a time over the minutes after it — and until that is finished the
 * table holds a *slice*, which no top ten and no "all-time best" may be read off. The migration's
 * own statements are what raise the fact the Records section covers itself up with, so they are put
 * to a real database here rather than described in prose: [RECORD_FILL_TABLE_SQL] and
 * [RAISE_WHOLESALE_FILL_SQL] are the exact strings `MIGRATION_36_37` executes, and
 * [WHOLESALE_FILL_OWED_SQL] is the exact question [RecordFillDao] puts to them.
 *
 * **The one-Run history is the case worth having a test for.** A count of what is owed cannot tell
 * a wholesale fill from an ordinary Tuesday: a runner with a single Run in history comes out of this
 * upgrade owing exactly one scoring, which is the same one an ordinary Run finishing owes — and one
 * of those two must hide the Records section while the other must not. Nothing about the number
 * separates them; what separates them is that only one of them was raised by an upgrade, which is
 * what this table records.
 */
class RecordFillMigrationTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Enough of the sessions table for the migration to ask its question of, in the two states
        // that matter: finished, and still being recorded.
        db.exec("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, endTime INTEGER NOT NULL)")
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a history of one finished Run comes out of the upgrade owing a wholesale fill`() {
        db.exec("INSERT INTO sessions (id, endTime) VALUES (1, 1000)")

        upgrade()

        // One Run, one debt — and the whole of the runner's record book is in it. Read as a count
        // this is indistinguishable from a Run that finished ten seconds ago on an install that has
        // been measuring itself for years; read as a fact, it is the upgrade's own backfill.
        assertTrue(fillOwed())
    }

    @Test
    fun `a long history comes out of the upgrade owing a wholesale fill`() {
        (1..40).forEach { db.exec("INSERT INTO sessions (id, endTime) VALUES ($it, 1000)") }

        upgrade()

        assertTrue(fillOwed())
    }

    @Test
    fun `an install with nothing finished to measure owes no fill at all`() {
        // A phone that has never finished a Run, and one that is part-way through its first: there
        // is nothing for the pass to measure either way, so there is nothing to say it is measuring.
        db.exec("INSERT INTO sessions (id, endTime) VALUES (1, 0)")

        upgrade()

        assertFalse(fillOwed())
    }

    @Test
    fun `a fresh install has no row and owes nothing`() {
        // Room builds the table from the entity on a first install and no migration runs at all,
        // so the question is asked of an empty table. It has to answer false rather than blank the
        // Records section of a runner whose history has been measured as it was run.
        db.exec(RECORD_FILL_TABLE_SQL)

        assertFalse(fillOwed())
    }

    @Test
    fun `the fill is handed back once the pass has been through history`() {
        db.exec("INSERT INTO sessions (id, endTime) VALUES (1, 1000)")
        upgrade()

        // What SessionRepository writes when the launch pass has measured everything it found.
        db.exec("INSERT OR REPLACE INTO record_fill (id, wholesaleFillOwed) VALUES (0, 0)")

        assertFalse(fillOwed())
    }

    @Test
    fun `an upgrade over a database that already holds the row raises it again`() {
        // A restore can put an older archive in front of an install that has already been through
        // this once — a `.db` from before v37 restored onto a phone whose own fill finished months
        // ago. The row that is there describes the history that has just been replaced, so the
        // migration has to overwrite it rather than leave the new history looking measured.
        db.exec(RECORD_FILL_TABLE_SQL)
        db.exec("INSERT INTO record_fill (id, wholesaleFillOwed) VALUES (0, 0)")
        db.exec("INSERT INTO sessions (id, endTime) VALUES (1, 1000)")

        upgrade()

        assertTrue(fillOwed())
    }

    /** The two statements `MIGRATION_36_37` runs to write the fill down, exactly as it runs them. */
    private fun upgrade() {
        db.exec(RECORD_FILL_TABLE_SQL)
        db.exec(RAISE_WHOLESALE_FILL_SQL)
    }

    /** The DAO's own read ([WHOLESALE_FILL_OWED_SQL]), run against the real table. */
    private fun fillOwed(): Boolean =
        db.createStatement().use { statement ->
            statement.executeQuery(WHOLESALE_FILL_OWED_SQL).use { cursor ->
                cursor.next()
                cursor.getBoolean(1)
            }
        }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
