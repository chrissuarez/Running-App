package com.example.runningapp.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the upgrade at #399 does to a library kept before #354 — see [libraryRedrawn].
 *
 * The claim under test is the one the ticket is about: **after the pass, offering the app a file it
 * already imported finds the row it already has.** So the tests do not describe the redraw in the
 * abstract; they stage a row the way the old code wrote it, redraw it, and then check the line
 * against the line [courseOf] draws from the very same places today — which is exactly what
 * `findRouteByPolyline` will be asked to match.
 */
class LibraryRedrawnTest {

    /** A line of places along a road, dense enough that thinning has something to take out. */
    private fun straightRoad(points: Int): List<RoutePoint> = (0 until points).map {
        RoutePoint(51.5 + it * 0.000_01, -0.12, elevationMeters = null)
    }

    /** A row exactly as the pre-#354 importer wrote it: every point the file held, unthinned. */
    private fun importedBefore354(id: Long, points: List<RoutePoint>, climb: Double? = null) =
        RouteAsKept(
            id = id,
            distanceMeters = routeDistanceMeters(points),
            elevationGainMeters = climb,
            polyline = RoutePolyline.encode(points),
        )

    @Test
    fun `an empty library is left alone`() {
        val redrawn = libraryRedrawn(emptyList())

        assertEquals(emptyList<RouteRedrawn>(), redrawn.redrawn)
        assertEquals(emptyList<RouteMerged>(), redrawn.merged)
    }

    @Test
    fun `a row already drawn by the new rule is not written at all`() {
        val course = courseOf(straightRoad(points = 200))
        val alreadyRight = RouteAsKept(
            id = 1,
            distanceMeters = routeDistanceMeters(course.line),
            elevationGainMeters = 12.0,
            polyline = RoutePolyline.encode(course.line),
        )

        val redrawn = libraryRedrawn(listOf(alreadyRight))

        assertEquals(emptyList<RouteRedrawn>(), redrawn.redrawn)
        assertEquals(emptyList<RouteMerged>(), redrawn.merged)
    }

    @Test
    fun `an imported row is redrawn onto the line its own file makes today`() {
        val file = straightRoad(points = 200)

        val redrawn = libraryRedrawn(listOf(importedBefore354(id = 1, points = file)))

        assertEquals(1, redrawn.redrawn.size)
        // The whole point of the pass: the row now holds the very text a re-import would look for.
        assertEquals(
            RoutePolyline.encode(courseOf(file).line),
            redrawn.redrawn.single().polyline,
        )
    }

    @Test
    fun `a redrawn row banks the distance along the line it now holds`() {
        val file = straightRoad(points = 200)
        val kept = importedBefore354(id = 1, points = file)

        val row = libraryRedrawn(listOf(kept)).redrawn.single()

        assertEquals(
            routeDistanceMeters(courseOf(file).line),
            row.distanceMeters,
            0.000_001,
        )
        // Thinning really did cut something, or this test would prove nothing.
        assertTrue(RoutePolyline.decode(row.polyline).size < file.size)
    }

    @Test
    fun `a redrawn row keeps the climb it banked, which no pass can measure again`() {
        val kept = importedBefore354(id = 1, points = straightRoad(points = 200), climb = 84.0)

        val row = libraryRedrawn(listOf(kept)).redrawn.single()

        assertEquals(84.0, row.elevationGainMeters)
    }

    @Test
    fun `a row whose line will not decode is left completely alone`() {
        val damaged = RouteAsKept(id = 1, distanceMeters = 900.0, elevationGainMeters = null, polyline = "junk")

        val redrawn = libraryRedrawn(listOf(damaged))

        assertEquals(emptyList<RouteRedrawn>(), redrawn.redrawn)
        assertEquals(emptyList<RouteMerged>(), redrawn.merged)
    }

    @Test
    fun `a run saved as a course and that run's own file become one row`() {
        val file = straightRoad(points = 200)
        // What the Run door already wrote — thinned, so a different line from the file's every point.
        val fromRun = RouteAsKept(
            id = 1,
            distanceMeters = 7.0,
            elevationGainMeters = 9.0,
            polyline = RoutePolyline.encode(courseOf(file).line),
        )
        val imported = importedBefore354(id = 2, points = file)

        val redrawn = libraryRedrawn(listOf(fromRun, imported))

        assertEquals(listOf(RouteMerged(lostId = 2, keptId = 1)), redrawn.merged)
    }

    @Test
    fun `the lower id survives a merge, whichever order the rows arrive in`() {
        val file = straightRoad(points = 200)
        val older = importedBefore354(id = 3, points = file)
        val newer = importedBefore354(id = 9, points = file)

        val redrawn = libraryRedrawn(listOf(newer, older))

        assertEquals(listOf(RouteMerged(lostId = 9, keptId = 3)), redrawn.merged)
        assertEquals(listOf(3L), redrawn.redrawn.map { it.id })
    }

    @Test
    fun `a survivor with no climb takes the climb of a row it absorbed`() {
        val file = straightRoad(points = 200)
        val silent = importedBefore354(id = 1, points = file, climb = null)
        val measured = importedBefore354(id = 2, points = file, climb = 61.0)

        val row = libraryRedrawn(listOf(silent, measured)).redrawn.single()

        assertEquals(61.0, row.elevationGainMeters)
    }

    @Test
    fun `a survivor that banked a climb does not lose it to a silent row`() {
        val file = straightRoad(points = 200)
        val measured = importedBefore354(id = 1, points = file, climb = 61.0)
        val silent = importedBefore354(id = 2, points = file, climb = null)

        val row = libraryRedrawn(listOf(measured, silent)).redrawn.single()

        assertEquals(61.0, row.elevationGainMeters)
    }

    @Test
    fun `two courses that are not the same line are both kept`() {
        val here = importedBefore354(id = 1, points = straightRoad(points = 200))
        val elsewhere = RouteAsKept(
            id = 2,
            distanceMeters = 0.0,
            elevationGainMeters = null,
            polyline = RoutePolyline.encode(
                (0 until 200).map { RoutePoint(48.85 + it * 0.000_01, 2.29, null) }
            ),
        )

        val redrawn = libraryRedrawn(listOf(here, elsewhere))

        assertEquals(emptyList<RouteMerged>(), redrawn.merged)
        assertEquals(listOf(1L, 2L), redrawn.redrawn.map { it.id })
    }

    /**
     * A place on the ground, so far north and east of one corner of London — the same sheet
     * `OneRunOneRouteTest` lays its boundary case out on.
     */
    private fun place(northMeters: Double, eastMeters: Double) = RoutePoint(
        latitude = 51.5 + northMeters / 111_320.0,
        longitude = -0.1 + eastMeters / (111_320.0 * 0.6225),
        elevationMeters = null,
    )

    /**
     * The one thing this pass cannot reach: a place a pre-#354 Run door had already thrown away.
     *
     * That door thinned before it snapped, so a place a hair inside the two metres was dropped and
     * never written down — while the same place, snapped first as both doors snap it today, sits a
     * hair *outside* and is kept (`OneRunOneRouteTest` pins that pair). Thinning only removes, so
     * the redraw has nothing to put back: the row keeps its two points, and that Run's own GPX
     * handed back still draws three. The library still holds that course twice, and #402 holds the
     * question of what to do about it. Written down here so the pass is not read as promising more
     * than it does.
     */
    @Test
    fun `a place the old Run door threw away is not brought back by the redraw`() {
        val walk = listOf(
            place(northMeters = 0.0, eastMeters = 0.0),
            place(northMeters = 100.0, eastMeters = 1.9995),
            place(northMeters = 200.0, eastMeters = 0.0),
        )
        // The row as that door left it: the ends only, the middle place already gone.
        val asTheOldDoorLeftIt = RouteAsKept(
            id = 1,
            distanceMeters = routeDistanceMeters(listOf(walk.first(), walk.last())),
            elevationGainMeters = null,
            polyline = RoutePolyline.encode(listOf(walk.first(), walk.last())),
        )

        val redrawn = libraryRedrawn(listOf(asTheOldDoorLeftIt))

        // The line does not move, because there is nothing left in the row for the redraw to work
        // on — only the distance is re-measured along it.
        assertEquals(asTheOldDoorLeftIt.polyline, redrawn.redrawn.single().polyline)
        // And the walk itself, drawn today, is a longer line — so the two do not match, which is
        // exactly the residue #402 is about.
        assertEquals(3, courseOf(walk).line.size)
        assertTrue(
            RoutePolyline.encode(courseOf(walk).line) != asTheOldDoorLeftIt.polyline
        )
    }
}
