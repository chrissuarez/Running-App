package com.example.runningapp.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What the v39 to v40 upgrade creates, against a real SQLite database held in memory (#371).
 *
 * [WALK_MARK_DEBTS_TABLE_SQL] is the exact string `MIGRATION_39_40` executes, so it is put to a real
 * database here rather than described in prose — [RecordFillMigrationTest]'s reason exactly.
 *
 * **The cascade is the claim worth testing.** A debt is a Run's id and the word said about it, and
 * Room hands a deleted Run's id to the next Run written. A debt that outlived its Run would be the
 * wrong runner's word about a different Run, put onto its row at the next launch — the same fault
 * the in-memory word is dropped with its Run to avoid (#317), reached this time through the table.
 */
class WalkMarkDebtMigrationTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Room opens every database with this on; SQLite defaults it off, so a test that did not
        // ask for it would be testing a cascade that never fires.
        db.exec("PRAGMA foreign_keys = ON")
        // Enough of the sessions table for a debt to point at.
        db.exec("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, endTime INTEGER NOT NULL)")
        db.exec(WALK_MARK_DEBTS_TABLE_SQL)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a Run owes at most one mark, and the newest word is the one owed`() {
        db.exec("INSERT INTO sessions (id, endTime) VALUES (7, 1000)")

        db.exec("INSERT OR REPLACE INTO walk_mark_debts (sessionId, isWalk) VALUES (7, 1)")
        db.exec("INSERT OR REPLACE INTO walk_mark_debts (sessionId, isWalk) VALUES (7, 0)")

        assertEquals(listOf(7L to false), debtsOwed())
    }

    @Test
    fun `a debt goes with the Run it is about`() {
        db.exec("INSERT INTO sessions (id, endTime) VALUES (7, 1000)")
        db.exec("INSERT INTO walk_mark_debts (sessionId, isWalk) VALUES (7, 1)")

        db.exec("DELETE FROM sessions WHERE id = 7")

        assertEquals(emptyList<Pair<Long, Boolean>>(), debtsOwed())
    }

    private fun debtsOwed(): List<Pair<Long, Boolean>> =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT sessionId, isWalk FROM walk_mark_debts ORDER BY sessionId").use { rows ->
                buildList {
                    while (rows.next()) add(rows.getLong(1) to rows.getBoolean(2))
                }
            }
        }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
