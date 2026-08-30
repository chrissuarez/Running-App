package com.example.runningapp.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing in [RouteDao] rewrites a Route's line (#403).
 *
 * The rule itself is stated at [Route.polyline]; this is what keeps it. Two things in the app lean
 * on a line never moving under its id, and both fail silently rather than loudly if it ever does:
 * the library screen lists rows without their lines and fetches one when it needs it
 * ([RouteDao.getLibraryFlow]), and the thumbnail worked out from a line is kept against the Route's
 * id alone (`RoutesViewModel`). A future `UPDATE routes SET polyline` would leave the first reading
 * a line that is not the one it listed and the second drawing a course the row no longer holds —
 * neither of which any existing test would notice.
 *
 * Read off the source rather than off the annotations: Room's `@Query` is kept only as far as the
 * class file, so there is nothing at run time to ask. The file is checked to be there first, so a
 * moved DAO fails this test rather than passing it by finding nothing.
 */
class RouteLineIsWrittenOnceTest {

    private val daoSource = File("src/main/java/com/example/runningapp/data/Route.kt")

    @Test
    fun `the dao source is where this test thinks it is`() {
        assertTrue(
            "RouteDao has moved from ${daoSource.path}; this test is reading nothing",
            daoSource.isFile,
        )
        assertTrue(daoSource.readText().contains("interface RouteDao"))
    }

    /**
     * Every statement in [text] that writes a Route's line.
     *
     * The text has its string joins closed up first, so a statement spread over several source
     * lines reads as the one statement it is: `"UPDATE routes " + "SET polyline = ?"` is two lines
     * in the source and one statement to SQLite, and a check made line by line would walk straight
     * past it. Only the `SET` half is read — a `WHERE polyline = ?` is a statement finding a row by
     * its line, which is how the library recognises a course it already has.
     */
    private fun lineRewritesIn(text: String): List<String> {
        val closedUp = text.replace("\"", " ").replace("+", " ").replace(Regex("\\s+"), " ")
        return Regex("UPDATE routes.{0,200}", RegexOption.IGNORE_CASE)
            .findAll(closedUp)
            .map { it.value }
            .filter { it.substringBefore(" WHERE ", it).contains("polyline", ignoreCase = true) }
            .toList()
    }

    @Test
    fun `no query in the dao writes a route's line`() {
        assertEquals(
            "A Route's line is written once, at insert — see Route.polyline for what leans on it. " +
                "Changing that means changing the library screen and the thumbnail cache with it.",
            emptyList<String>(),
            lineRewritesIn(daoSource.readText()),
        )
    }

    /**
     * The check above really would catch one, spread over source lines as it would arrive.
     *
     * Without this the test above passes just as happily on a file it failed to understand.
     */
    @Test
    fun `a line rewrite spread over several source lines is found`() {
        val planted = """
            @Query(
                "UPDATE routes " +
                    "SET polyline = :line WHERE id = :routeId"
            )
            suspend fun redrawRoute(routeId: Long, line: String)
        """

        assertEquals(1, lineRewritesIn(planted).size)
    }

    /** And a query that merely *finds* a row by its line is not a rewrite of one. */
    @Test
    fun `finding a route by its line is not writing one`() {
        val finding = """
            @Query("UPDATE routes SET name = :name WHERE polyline = :polyline")
            suspend fun renameByLine(polyline: String, name: String)
        """

        assertEquals(emptyList<String>(), lineRewritesIn(finding))
    }
}
