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
 * The v44 to v45 upgrade, and the flip it makes room for (#466).
 *
 * [ADD_ROUTE_FLIPPED_SQL] and [FLIP_ROUTE_SQL] are the exact strings the migration and
 * [RouteDao.flipRoute] execute, put to a real SQLite database — [RouteFamilyMigrationTest]'s reason.
 *
 * **The claim worth testing is that a flip touches one thing.** The line is the Route's identity and
 * is never rewritten ([Route.polyline]); a flip is one bit beside it, so the line, the numbers and
 * every other row stay exactly as they were.
 */
class RouteFlipMigrationTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // The routes table as v44 left it.
        db.exec(
            """
            CREATE TABLE routes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                distanceMeters REAL NOT NULL,
                elevationGainMeters REAL,
                polyline TEXT NOT NULL,
                createdAtMillis INTEGER NOT NULL,
                source TEXT NOT NULL,
                family TEXT
            )
            """
        )
    }

    @After
    fun tearDown() = db.close()

    private fun keptRoute(id: Long) = db.exec(
        "INSERT INTO routes (id, name, distanceMeters, elevationGainMeters, polyline, " +
            "createdAtMillis, source) VALUES ($id, 'Loop $id', 3500.0, 12.0, 'abc$id', 100, 'imported')"
    )

    @Test
    fun `every course kept before the upgrade goes the way it was saved`() {
        keptRoute(1)
        keptRoute(2)

        db.exec(ADD_ROUTE_FLIPPED_SQL)

        assertEquals(listOf(0, 0), listOf(flippedOf(1), flippedOf(2)))
    }

    @Test
    fun `a flip turns one course round, and a second flip turns it back`() {
        keptRoute(1)
        keptRoute(2)
        db.exec(ADD_ROUTE_FLIPPED_SQL)

        db.flip(1)
        assertEquals(1, flippedOf(1))
        assertEquals(0, flippedOf(2))

        db.flip(1)
        assertEquals(0, flippedOf(1))
    }

    @Test
    fun `a flip leaves the line and the numbers alone`() {
        keptRoute(1)
        db.exec(ADD_ROUTE_FLIPPED_SQL)

        db.flip(1)

        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT name, distanceMeters, elevationGainMeters, polyline FROM routes WHERE id = 1"
            ).use { row ->
                row.next()
                assertEquals("Loop 1", row.getString(1))
                assertEquals(3500.0, row.getDouble(2), 0.0)
                assertEquals(12.0, row.getDouble(3), 0.0)
                assertEquals("abc1", row.getString(4))
            }
        }
    }

    @Test
    fun `the upgrade is one the database will actually run`() {
        val registered = appDatabaseMigrations { HrProfile(maxHr = 190) }

        assertEquals(44, MIGRATION_44_45.startVersion)
        assertEquals(45, MIGRATION_44_45.endVersion)
        assertTrue(registered.any { it === MIGRATION_44_45 })
    }

    private fun Connection.flip(id: Long) =
        prepareStatement(FLIP_ROUTE_SQL.replace(":routeId", "?")).use { statement ->
            statement.setLong(1, id)
            statement.execute()
        }

    private fun flippedOf(id: Long): Int = db.createStatement().use { statement ->
        statement.executeQuery("SELECT flipped FROM routes WHERE id = $id").use { row ->
            row.next()
            row.getInt(1)
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
