package com.example.runningapp.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What the v40 to v41 upgrade raises, against a real SQLite database held in memory (#349).
 *
 * [HISTORY_DEBTS_TABLE_SQL], [RAISE_MOVING_TIME_DEBT_SQL] and [RAISE_EFFORT_SCORE_DEBT_SQL] are the
 * exact strings `MIGRATION_40_41` executes, so they are put to a real database here rather than
 * described in prose — [RecordFillMigrationTest]'s reason exactly.
 *
 * **What is raised is the claim worth testing.** The debt covers a Run Summary up for the whole of
 * the first launch after the upgrade, so raising one on an install with nothing to measure would be
 * the app saying "still measuring your runs" about work nobody is doing — and failing to raise one
 * where history really is half-measured is the fault #349 exists to close.
 */
class HistoryDebtMigrationTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Enough of the sessions table for the two raises to read.
        db.exec(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                endTime INTEGER NOT NULL,
                runMode TEXT NOT NULL,
                movingTimeSeconds INTEGER,
                effortScore INTEGER
            )
            """
        )
        db.exec(HISTORY_DEBTS_TABLE_SQL)
    }

    @After
    fun tearDown() = db.close()

    private fun run(
        id: Long,
        endTime: Long = 1000,
        runMode: String = "outdoor",
        movingTime: String = "NULL",
        effortScore: String = "NULL",
    ) = db.exec(
        "INSERT INTO sessions (id, endTime, runMode, movingTimeSeconds, effortScore) " +
            "VALUES ($id, $endTime, '$runMode', $movingTime, $effortScore)"
    )

    private fun raise() {
        db.exec(RAISE_MOVING_TIME_DEBT_SQL)
        db.exec(RAISE_EFFORT_SCORE_DEBT_SQL)
    }

    @Test
    fun `a history already measured owes nothing`() {
        run(id = 1, movingTime = "600", effortScore = "42")

        raise()

        assertEquals(emptyList<String>(), debtsOwed())
    }

    @Test
    fun `an empty history owes nothing`() {
        raise()

        assertEquals(emptyList<String>(), debtsOwed())
    }

    @Test
    fun `a run recorded before moving time shipped owes that pass`() {
        run(id = 1, movingTime = "NULL", effortScore = "42")

        raise()

        assertEquals(listOf(HistoryPass.MOVING_TIME), debtsOwed())
    }

    @Test
    fun `a run recorded before the effort score shipped owes that pass`() {
        run(id = 1, movingTime = "600", effortScore = "NULL")

        raise()

        assertEquals(listOf(HistoryPass.EFFORT_SCORES), debtsOwed())
    }

    @Test
    fun `a treadmill run owes no moving time, having no track to measure`() {
        // The pass's own work list is outdoor-only, and the raise is that query written as an
        // existence — so a history of nothing but treadmill Runs must not be covered up.
        run(id = 1, runMode = "treadmill", movingTime = "NULL", effortScore = "42")

        raise()

        assertEquals(emptyList<String>(), debtsOwed())
    }

    @Test
    fun `a run still being recorded raises nothing`() {
        // endTime 0 is a Run in progress. Neither pass reaches one, so neither is owed for it.
        run(id = 1, endTime = 0, movingTime = "NULL", effortScore = "NULL")

        raise()

        assertEquals(emptyList<String>(), debtsOwed())
    }

    @Test
    fun `each pass is written down at most once, however much history owes it`() {
        run(id = 1)
        run(id = 2)
        run(id = 3)

        raise()
        // Re-running the upgrade's statements must not double anything: the fact is about the pass,
        // not about any Run.
        raise()

        assertEquals(listOf(HistoryPass.EFFORT_SCORES, HistoryPass.MOVING_TIME), debtsOwed())
    }

    private fun debtsOwed(): List<String> =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT pass FROM history_debts ORDER BY pass").use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1))
                }
            }
        }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
