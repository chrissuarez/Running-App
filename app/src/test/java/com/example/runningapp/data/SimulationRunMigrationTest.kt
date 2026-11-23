package com.example.runningapp.data

import com.example.runningapp.HrProfile
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The v45 to v46 upgrade, which gives every Run the mark of whether Simulation was on (#560).
 *
 * [ADD_SIMULATION_RUN_SQL] is the exact string the migration executes, put to a real SQLite database
 * — [RouteFamilyMigrationTest]'s reason. Nothing on a v45 row says it was simulated, and the upgrade
 * guesses nothing: every Run kept before it reads as a real one.
 */
class SimulationRunMigrationTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // The part of the sessions table this upgrade touches, as v45 left it.
        db.exec(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTime INTEGER NOT NULL,
                endTime INTEGER NOT NULL,
                isWalk INTEGER NOT NULL DEFAULT 0
            )
            """
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `every Run kept before the upgrade reads as a real Run`() {
        db.exec("INSERT INTO sessions (id, startTime, endTime) VALUES (1, 100, 200)")
        db.exec("INSERT INTO sessions (id, startTime, endTime) VALUES (2, 300, 400)")

        db.exec(ADD_SIMULATION_RUN_SQL)

        assertEquals(listOf(0, 0), listOf(simulationOf(1), simulationOf(2)))
    }

    @Test
    fun `the upgrade is one the database will actually run`() {
        val registered = appDatabaseMigrations { HrProfile(maxHr = 190) }

        assertEquals(45, MIGRATION_45_46.startVersion)
        assertEquals(46, MIGRATION_45_46.endVersion)
        assertTrue(registered.any { it === MIGRATION_45_46 })
    }

    private fun simulationOf(id: Long): Int = db.createStatement().use { statement ->
        statement.executeQuery("SELECT isSimulationRun FROM sessions WHERE id = $id").use { row ->
            row.next()
            row.getInt(1)
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
