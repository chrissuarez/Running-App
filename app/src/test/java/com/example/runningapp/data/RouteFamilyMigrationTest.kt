package com.example.runningapp.data

import com.example.runningapp.HrProfile
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the v42 to v43 upgrade does to a library already kept (#421).
 *
 * [ADD_ROUTE_FAMILY_SQL] is the exact string `MIGRATION_42_43` executes, put to a real SQLite
 * database here rather than described in prose — [RecordFillMigrationTest]'s reason exactly.
 *
 * **The claim worth testing is that nothing else moves.** Every course already kept comes through
 * belonging to no family, which is what was true of it before, and its name, its numbers and its
 * line are exactly as they were. A runner who upgrades has the library they had.
 */
class RouteFamilyMigrationTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // The routes table as v42 left it: the six columns, and nothing else.
        db.exec(
            """
            CREATE TABLE routes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                distanceMeters REAL NOT NULL,
                elevationGainMeters REAL,
                polyline TEXT NOT NULL,
                createdAtMillis INTEGER NOT NULL,
                source TEXT NOT NULL
            )
            """
        )
    }

    @After
    fun tearDown() = db.close()

    private fun keptRoute(id: Long, name: String, climb: String = "NULL") = db.exec(
        "INSERT INTO routes (id, name, distanceMeters, elevationGainMeters, polyline, " +
            "createdAtMillis, source) VALUES ($id, '$name', 5000.0, $climb, 'abc', 100, 'imported')"
    )

    @Test
    fun `a course kept before the upgrade belongs to no family after it`() {
        keptRoute(id = 1, name = "Regents Park loop")

        db.exec(ADD_ROUTE_FAMILY_SQL)

        assertNull(familyOf(1))
    }

    @Test
    fun `the upgrade leaves every other thing on the row alone`() {
        keptRoute(id = 1, name = "Regents Park loop", climb = "27.4")

        db.exec(ADD_ROUTE_FAMILY_SQL)

        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT name, distanceMeters, elevationGainMeters, polyline, createdAtMillis, " +
                    "source FROM routes WHERE id = 1"
            ).use { row ->
                row.next()
                assertEquals("Regents Park loop", row.getString(1))
                assertEquals(5000.0, row.getDouble(2), 0.0)
                assertEquals(27.4, row.getDouble(3), 0.0)
                assertEquals("abc", row.getString(4))
                assertEquals(100L, row.getLong(5))
                assertEquals("imported", row.getString(6))
            }
        }
    }

    @Test
    fun `a whole library comes through, every row of it`() {
        keptRoute(id = 1, name = "One")
        keptRoute(id = 2, name = "Two")
        keptRoute(id = 3, name = "Three")

        db.exec(ADD_ROUTE_FAMILY_SQL)

        assertEquals(3, countRoutes())
        assertEquals(listOf(null, null, null), listOf(familyOf(1), familyOf(2), familyOf(3)))
    }

    @Test
    fun `an empty library upgrades to an empty library`() {
        db.exec(ADD_ROUTE_FAMILY_SQL)

        assertEquals(0, countRoutes())
    }

    /**
     * The upgrade is only reached if the database is told about it: a migration written and left out
     * of the list is a launch that refuses to open, and no other test here would notice.
     */
    @Test
    fun `the upgrade is one the database will actually run`() {
        val registered = appDatabaseMigrations { HrProfile(maxHr = 190) }

        assertEquals(42, MIGRATION_42_43.startVersion)
        assertEquals(43, MIGRATION_42_43.endVersion)
        assertTrue(registered.any { it === MIGRATION_42_43 })
    }

    @Test
    fun `a family can be written once the column is there`() {
        keptRoute(id = 1, name = "Cuckoo Trail 5k")
        db.exec(ADD_ROUTE_FAMILY_SQL)

        db.exec("UPDATE routes SET family = 'Cuckoo Trail' WHERE id = 1")

        assertEquals("Cuckoo Trail", familyOf(1))
    }

    private fun familyOf(id: Long): String? = db.createStatement().use { statement ->
        statement.executeQuery("SELECT family FROM routes WHERE id = $id").use { row ->
            row.next()
            row.getString(1)
        }
    }

    private fun countRoutes(): Int = db.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM routes").use { row ->
            row.next()
            row.getInt(1)
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
