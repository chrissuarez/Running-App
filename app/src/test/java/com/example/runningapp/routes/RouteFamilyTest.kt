package com.example.runningapp.routes

import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.RouteLastRunRow
import com.example.runningapp.data.RouteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules a family of courses keeps (#421, #436): who a course's siblings are, which length its
 * page opens on, and the names the box offers.
 *
 * Moved out of `RouteFamiliesTest` with the rules themselves (#480) — they are the library's, not
 * the screen's, and the words and the folding stayed behind with the screen.
 */
class RouteFamilyTest {

    private fun header(
        id: Long,
        name: String = "Route $id",
        distanceMeters: Double = 5_000.0,
        family: String? = null,
    ) = RouteHeader(
        id = id,
        name = name,
        distanceMeters = distanceMeters,
        elevationGainMeters = null,
        createdAtMillis = id,
        source = RouteSource.IMPORTED,
        family = family,
    )

    // --- Siblings and where a page lands ---

    @Test
    fun `siblings come back shortest first`() {
        val library = listOf(
            header(3, distanceMeters = 8_000.0, family = "Cuckoo Trail"),
            header(2, distanceMeters = 12_000.0, family = "Cuckoo Trail"),
            header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail"),
        )

        assertEquals(listOf(1L, 3L, 2L), routeSiblings(library, routeId = 2).map { it.id })
    }

    @Test
    fun `a course with no family is its own only sibling`() {
        val library = listOf(header(1), header(2, family = "Cuckoo Trail"))

        assertEquals(listOf(1L), routeSiblings(library, routeId = 1).map { it.id })
    }

    @Test
    fun `a deleted course has no siblings at all`() {
        assertEquals(emptyList<RouteHeader>(), routeSiblings(listOf(header(1)), routeId = 9))
    }

    @Test
    fun `a family opens on the length run most recently`() {
        val siblings = listOf(
            header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail"),
            header(2, distanceMeters = 8_000.0, family = "Cuckoo Trail"),
            header(3, distanceMeters = 12_000.0, family = "Cuckoo Trail"),
        )

        val landing = routeFamilyLandingId(
            siblings,
            listOf(RouteLastRunRow(1, 1_000), RouteLastRunRow(2, 9_000)),
        )

        assertEquals(2L, landing)
    }

    @Test
    fun `a family nobody has run opens on the shortest`() {
        val siblings = listOf(
            header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail"),
            header(2, distanceMeters = 8_000.0, family = "Cuckoo Trail"),
        )

        assertEquals(1L, routeFamilyLandingId(siblings, emptyList()))
    }

    @Test
    fun `a Run on a course outside the family cannot decide where the page lands`() {
        val siblings = listOf(
            header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail"),
            header(2, distanceMeters = 8_000.0, family = "Cuckoo Trail"),
        )

        val landing = routeFamilyLandingId(siblings, listOf(RouteLastRunRow(routeId = 99, 9_000)))

        assertEquals(1L, landing)
    }

    @Test
    fun `two lengths last run on the same millisecond fall to the shorter`() {
        val siblings = listOf(
            header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail"),
            header(2, distanceMeters = 8_000.0, family = "Cuckoo Trail"),
        )

        val landing = routeFamilyLandingId(
            siblings,
            listOf(RouteLastRunRow(1, 5_000), RouteLastRunRow(2, 5_000)),
        )

        assertEquals(1L, landing)
    }

    @Test
    fun `a course deleted out from under the page lands nowhere`() {
        assertNull(routeFamilyLandingId(emptyList(), emptyList()))
    }

    // --- The landing counts the Runs a length only recognises (#436) ---

    @Test
    fun `a length nobody wrote down is still the length run most recently`() {
        val theFiveK = shapeAt(51.5)
        val theEightK = shapeAt(52.5)

        val lastRuns = routeFamilyLastRuns(
            remembered = emptyList(),
            courses = listOf(
                CourseShape(routeId = 1, name = "Cuckoo 5k", shape = theFiveK),
                CourseShape(routeId = 2, name = "Cuckoo 8k", shape = theEightK),
            ),
            shaped = listOf(runOver(theEightK, sessionId = 7, startTime = 9_000)),
        )

        assertEquals(listOf(RouteLastRunRow(2, 9_000)), lastRuns)
    }

    @Test
    fun `the later of the two histories is the one a length is judged on`() {
        val theFiveK = shapeAt(51.5)

        val lastRuns = routeFamilyLastRuns(
            remembered = listOf(RouteLastRunRow(1, 9_000)),
            courses = listOf(CourseShape(routeId = 1, name = "Cuckoo 5k", shape = theFiveK)),
            shaped = listOf(runOver(theFiveK, sessionId = 7, startTime = 1_000)),
        )

        assertEquals(listOf(RouteLastRunRow(1, 9_000)), lastRuns)
    }

    @Test
    fun `a run over other ground does not move the length it is not on`() {
        val theFiveK = shapeAt(51.5)
        val elsewhere = shapeAt(53.5)

        val lastRuns = routeFamilyLastRuns(
            remembered = emptyList(),
            courses = listOf(CourseShape(routeId = 1, name = "Cuckoo 5k", shape = theFiveK)),
            shaped = listOf(runOver(elsewhere, sessionId = 7, startTime = 9_000)),
        )

        assertEquals(emptyList<RouteLastRunRow>(), lastRuns)
    }

    /** A course still owed its measurement keeps whatever was written down on it. */
    @Test
    fun `a length with no shape yet keeps the runs remembered on it`() {
        val theEightK = shapeAt(52.5)

        val lastRuns = routeFamilyLastRuns(
            remembered = listOf(RouteLastRunRow(1, 5_000)),
            courses = listOf(CourseShape(routeId = 2, name = "Cuckoo 8k", shape = theEightK)),
            shaped = listOf(runOver(theEightK, sessionId = 7, startTime = 1_000)),
        )

        assertEquals(
            setOf(RouteLastRunRow(1, 5_000), RouteLastRunRow(2, 1_000)),
            lastRuns.toSet(),
        )
    }

    /** Nothing to recognise against is the read the app made before #436, unchanged. */
    @Test
    fun `a family with no shapes at all is judged on what was written down`() {
        val remembered = listOf(RouteLastRunRow(1, 5_000))

        assertEquals(
            remembered,
            routeFamilyLastRuns(remembered, courses = emptyList(), shaped = emptyList()),
        )
    }

    // --- The names the box offers ---

    @Test
    fun `the box offers each family name once, in order`() {
        val library = listOf(
            header(1, family = "Downs"),
            header(2, family = "Cuckoo Trail"),
            header(3, family = "Downs"),
            header(4),
        )

        assertEquals(listOf("Cuckoo Trail", "Downs"), routeFamilyNames(library))
    }
}
