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
 * What the v43 to v44 upgrade does to a library already kept (#74).
 *
 * [CREATE_ROUTE_SHAPES_SQL] is the exact string `MIGRATION_43_44` executes, put to a real SQLite
 * database here rather than described in prose — [RouteFamilyMigrationTest]'s reason exactly.
 *
 * **The two claims worth testing are that the upgrade touches no course, and that every course
 * already kept comes out of it owing a shape.** The second is the whole backfill: the table's own
 * emptiness is the debt, so a library that came through with nothing owed would be a library whose
 * pages stay empty for ever.
 */
class RouteShapeMigrationTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.exec("PRAGMA foreign_keys=ON")
        // The routes table as v43 left it.
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

    private fun keptRoute(id: Long, name: String) = db.exec(
        "INSERT INTO routes (id, name, distanceMeters, elevationGainMeters, polyline, " +
            "createdAtMillis, source) VALUES ($id, '$name', 5000.0, NULL, 'abc', 100, 'imported')"
    )

    @Test
    fun `every course kept before the upgrade comes out of it owing a shape`() {
        keptRoute(id = 1, name = "Regents Park loop")
        keptRoute(id = 2, name = "Cuckoo Trail 8k")

        db.exec(CREATE_ROUTE_SHAPES_SQL)

        assertEquals(listOf(1L, 2L), courseIdsMissingShapes())
    }

    @Test
    fun `the upgrade leaves the library exactly as it was`() {
        keptRoute(id = 1, name = "Regents Park loop")

        db.exec(CREATE_ROUTE_SHAPES_SQL)

        db.createStatement().use { statement ->
            statement.executeQuery("SELECT name, polyline FROM routes WHERE id = 1").use { row ->
                row.next()
                assertEquals("Regents Park loop", row.getString(1))
                assertEquals("abc", row.getString(2))
            }
        }
        assertEquals(0, countOf("SELECT COUNT(*) FROM route_shapes"))
    }

    @Test
    fun `an empty library upgrades to an empty library owing nothing`() {
        db.exec(CREATE_ROUTE_SHAPES_SQL)

        assertEquals(emptyList<Long>(), courseIdsMissingShapes())
    }

    @Test
    fun `a shape written after the upgrade is owed no longer, and goes when its course does`() {
        keptRoute(id = 1, name = "Regents Park loop")
        db.exec(CREATE_ROUTE_SHAPES_SQL)

        db.exec("INSERT INTO route_shapes (routeId, shape, distanceMeters) VALUES (1, 'xyz', 5000.0)")
        assertEquals(emptyList<Long>(), courseIdsMissingShapes())

        db.exec("DELETE FROM routes WHERE id = 1")

        assertEquals(0, countOf("SELECT COUNT(*) FROM route_shapes"))
    }

    /**
     * The upgrade is only reached if the database is told about it: a migration written and left out
     * of the list is a launch that refuses to open, and no other test here would notice.
     */
    @Test
    fun `the upgrade is one the database will actually run`() {
        val registered = appDatabaseMigrations { HrProfile(maxHr = 190) }

        assertEquals(43, MIGRATION_43_44.startVersion)
        assertEquals(44, MIGRATION_43_44.endVersion)
        assertTrue(registered.any { it === MIGRATION_43_44 })
    }

    /** The DAO's own debt read ([RouteShapeDao.getRouteIdsMissingShapes]), against the real tables. */
    private fun courseIdsMissingShapes(): List<Long> {
        val ids = mutableListOf<Long>()
        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT id FROM routes WHERE id NOT IN (SELECT routeId FROM route_shapes) ORDER BY id ASC"
            ).use { cursor ->
                while (cursor.next()) ids += cursor.getLong(1)
            }
        }
        return ids
    }

    private fun countOf(sql: String) = db.createStatement().use { statement ->
        statement.executeQuery(sql).use { cursor ->
            cursor.next()
            cursor.getInt(1)
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
