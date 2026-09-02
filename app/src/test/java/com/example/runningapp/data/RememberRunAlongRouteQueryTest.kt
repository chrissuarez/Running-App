package com.example.runningapp.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The statement that remembers a Run on the course traced off it (#420), against a real SQLite
 * database held in memory.
 *
 * Run here rather than through a fake DAO because what is being proved is not that the saver asks
 * the right question — it is that the question and the write are *one* statement. `ranAlongRouteId`
 * is what the Run set out to do and nothing may move it ([RunnerSession.ranAlongRouteId]); read it,
 * decide, then write, and two saves of the same Run could each find it empty and the second would
 * overwrite the first's answer. Only the statement itself can show there is no such in-between, and
 * only a real engine can run it.
 */
class RememberRunAlongRouteQueryTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.exec(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTime INTEGER NOT NULL,
                ranAlongRouteId INTEGER
            )
            """
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a run following no course is remembered on the one traced off it`() {
        givenRun(7)

        assertEquals(1, remember(sessionId = 7, routeId = 3))
        assertEquals(3L, courseOf(7))
    }

    /**
     * The Run set out along course A and was later saved as a course of its own. It keeps A: what it
     * set out to do is a fact about that morning, and saving its ground afterwards does not change it.
     */
    @Test
    fun `a run that already names a course keeps it`() {
        givenRun(7, ranAlongRouteId = 1)

        assertEquals(0, remember(sessionId = 7, routeId = 3))
        assertEquals(1L, courseOf(7))
    }

    /** The Run was deleted while the save was in flight. Nothing is written, and nothing throws. */
    @Test
    fun `a run that is gone is nothing to remember`() {
        assertEquals(0, remember(sessionId = 7, routeId = 3))
    }

    @Test
    fun `only the run named is remembered`() {
        givenRun(7)
        givenRun(8)

        assertEquals(1, remember(sessionId = 7, routeId = 3))
        assertNull(courseOf(8))
    }

    /**
     * The statement the phone runs, run here — the same constant the DAO is annotated with, with
     * Room's named parameters swapped for JDBC's positional ones.
     */
    private fun remember(sessionId: Long, routeId: Long): Int {
        val sql = REMEMBER_RUN_ALONG_ROUTE.replace(":routeId", "?").replace(":sessionId", "?")
        return db.prepareStatement(sql).use {
            it.setLong(1, routeId)
            it.setLong(2, sessionId)
            it.executeUpdate()
        }
    }

    private fun givenRun(id: Long, ranAlongRouteId: Long? = null) = db.exec(
        "INSERT INTO sessions (id, startTime, ranAlongRouteId) VALUES " +
            "($id, 1700000000000, ${ranAlongRouteId ?: "NULL"})"
    )

    private fun courseOf(sessionId: Long): Long? =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT ranAlongRouteId FROM sessions WHERE id = $sessionId").use {
                if (!it.next()) null else it.getLong(1).takeIf { _ -> !it.wasNull() }
            }
        }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
