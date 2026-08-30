package com.example.runningapp.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What the v41 to v42 upgrade writes, against a real SQLite database held in memory (#399).
 *
 * [READ_LIBRARY_AS_KEPT_SQL], [REDIRECT_RAN_ALONG_MERGED_ROUTE_SQL], [DROP_MERGED_ROUTE_SQL] and
 * [REDRAW_ROUTE_SQL] are the exact strings `MIGRATION_41_42` executes, so they are put to a real
 * database here rather than described in prose — [HistoryDebtMigrationTest]'s reason exactly.
 *
 * What the pass *decides* is not tested here: that is pure Kotlin and is pinned by
 * `LibraryRedrawnTest`. What is tested here is the half only a database can answer — that dropping
 * the losing row of a merged pair does not leave a past Run pointing at a Route that is gone.
 */
class LibraryRedrawMigrationTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
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
        // Enough of the sessions table for the redirect to read.
        db.exec(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                ranAlongRouteId INTEGER
            )
            """
        )
    }

    @After
    fun tearDown() = db.close()

    private fun route(id: Long, name: String, polyline: String, climb: String = "NULL") = db.exec(
        "INSERT INTO routes (id, name, distanceMeters, elevationGainMeters, polyline, " +
            "createdAtMillis, source) VALUES ($id, '$name', 100.0, $climb, '$polyline', 7, 'imported')"
    )

    private fun run(id: Long, ranAlong: String) =
        db.exec("INSERT INTO sessions (id, ranAlongRouteId) VALUES ($id, $ranAlong)")

    private fun merge(lostId: Long, keptId: Long) {
        db.prepareStatement(REDIRECT_RAN_ALONG_MERGED_ROUTE_SQL).use {
            it.setLong(1, keptId)
            it.setLong(2, lostId)
            it.executeUpdate()
        }
        db.prepareStatement(DROP_MERGED_ROUTE_SQL).use {
            it.setLong(1, lostId)
            it.executeUpdate()
        }
    }

    @Test
    fun `the library is read in id order, which is what decides who survives a merge`() {
        route(id = 5, name = "later", polyline = "51.5,-0.1")
        route(id = 2, name = "earlier", polyline = "51.6,-0.1")

        val ids = ArrayList<Long>()
        db.createStatement().executeQuery(READ_LIBRARY_AS_KEPT_SQL).use {
            while (it.next()) ids += it.getLong("id")
        }

        assertEquals(listOf(2L, 5L), ids)
    }

    @Test
    fun `a null climb is read as a null and not as a nought`() {
        route(id = 1, name = "silent", polyline = "51.5,-0.1", climb = "NULL")

        db.createStatement().executeQuery(READ_LIBRARY_AS_KEPT_SQL).use {
            it.next()
            it.getDouble("elevationGainMeters")
            assertEquals(true, it.wasNull())
        }
    }

    @Test
    fun `a run that followed the losing row is sent to the row that survives`() {
        route(id = 1, name = "kept", polyline = "51.5,-0.1")
        route(id = 2, name = "lost", polyline = "51.5,-0.1")
        run(id = 10, ranAlong = "2")

        merge(lostId = 2, keptId = 1)

        assertEquals(listOf(1L), ranAlongRouteIds())
        assertEquals(listOf(1L), routeIds())
    }

    @Test
    fun `a run that followed no course at all is left alone by a merge`() {
        route(id = 1, name = "kept", polyline = "51.5,-0.1")
        route(id = 2, name = "lost", polyline = "51.5,-0.1")
        run(id = 10, ranAlong = "NULL")

        merge(lostId = 2, keptId = 1)

        assertEquals(emptyList<Long>(), ranAlongRouteIds())
    }

    @Test
    fun `a run that followed a route untouched by the merge still points at it`() {
        route(id = 1, name = "kept", polyline = "51.5,-0.1")
        route(id = 2, name = "lost", polyline = "51.5,-0.1")
        route(id = 3, name = "elsewhere", polyline = "48.8,2.2")
        run(id = 10, ranAlong = "3")

        merge(lostId = 2, keptId = 1)

        assertEquals(listOf(3L), ranAlongRouteIds())
    }

    @Test
    fun `a redrawn row keeps its name and its date`() {
        route(id = 1, name = "Tuesday hill loop", polyline = "51.5,-0.1")

        db.prepareStatement(REDRAW_ROUTE_SQL).use {
            it.setString(1, "51.5000000,-0.1000000")
            it.setDouble(2, 42.0)
            it.setNull(3, java.sql.Types.REAL)
            it.setLong(4, 1)
            it.executeUpdate()
        }

        db.createStatement().executeQuery("SELECT * FROM routes WHERE id = 1").use {
            it.next()
            assertEquals("Tuesday hill loop", it.getString("name"))
            assertEquals(7L, it.getLong("createdAtMillis"))
            assertEquals("imported", it.getString("source"))
            assertEquals("51.5000000,-0.1000000", it.getString("polyline"))
            assertEquals(42.0, it.getDouble("distanceMeters"), 0.000_001)
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun ranAlongRouteIds(): List<Long> {
        val ids = ArrayList<Long>()
        db.createStatement()
            .executeQuery("SELECT ranAlongRouteId FROM sessions WHERE ranAlongRouteId IS NOT NULL")
            .use { while (it.next()) ids += it.getLong(1) }
        return ids
    }

    private fun routeIds(): List<Long> {
        val ids = ArrayList<Long>()
        db.createStatement().executeQuery("SELECT id FROM routes ORDER BY id")
            .use { while (it.next()) ids += it.getLong(1) }
        return ids
    }
}
