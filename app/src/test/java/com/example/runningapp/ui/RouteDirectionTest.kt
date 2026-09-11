package com.example.runningapp.ui

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.RouteSource
import com.example.runningapp.recording.theShortWayRound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which way round a course goes, as its page draws it and as a Run sets out on it (#465, #466).
 *
 * The drawing is pinned in bearings rather than pixels: an arrow is a place on the line and the way
 * the line is heading there, and that is the whole of what the map is handed.
 */
class RouteDirectionTest {

    /** A straight line due north from Regent's Park, [meters] long, one fix every ten metres. */
    private fun northwards(meters: Int): List<MapFix> =
        (0..meters / 10).map { step -> MapFix(51.5 + step * 10 / 111_250.0, -0.15) }

    /** Due east along the same latitude. */
    private fun eastwards(meters: Int): List<MapFix> =
        (0..meters / 10).map { step -> MapFix(51.5, -0.15 + step * 10 / 69_400.0) }

    private fun assertBearing(expected: Double, actual: Double) =
        assertEquals(expected, actual, 1.0)

    @Test
    fun `every arrow on a line going north points north`() {
        val arrows = directionArrowsAlong(northwards(3_000))

        assertTrue(arrows.isNotEmpty())
        arrows.forEach { assertBearing(0.0, it.bearingDegrees) }
    }

    @Test
    fun `every arrow on a line going east points east`() {
        eastwards(3_000).let(::directionArrowsAlong).forEach { assertBearing(90.0, it.bearingDegrees) }
    }

    /** The same ground the other way round is the same arrows the other way round. */
    @Test
    fun `the line the other way round points every arrow the other way`() {
        northwards(3_000).asReversed().let(::directionArrowsAlong)
            .forEach { assertBearing(180.0, it.bearingDegrees) }
        eastwards(3_000).asReversed().let(::directionArrowsAlong)
            .forEach { assertBearing(270.0, it.bearingDegrees) }
    }

    /**
     * Never at either end. The start and finish have markers of their own, and on a loop the two are
     * the same place — an arrow there would sit on top of both.
     */
    @Test
    fun `no arrow sits on the start or the finish`() {
        val line = northwards(3_000)
        val arrows = directionArrowsAlong(line)

        arrows.forEach { arrow ->
            assertTrue(arrow.at.latitude > line.first().latitude + 100 / 111_250.0)
            assertTrue(arrow.at.latitude < line.last().latitude - 100 / 111_250.0)
        }
    }

    /** Spread along the ground evenly, and in the order the course is run. */
    @Test
    fun `the arrows run from start to finish in order`() {
        val arrows = directionArrowsAlong(northwards(4_000))

        assertEquals(arrows.sortedBy { it.at.latitude }, arrows)
    }

    /** Enough to read on a short loop, and not a picket fence on a half marathon. */
    @Test
    fun `a longer course has more arrows, within bounds`() {
        val short = directionArrowsAlong(northwards(500)).size
        val middling = directionArrowsAlong(northwards(3_500)).size
        val long = directionArrowsAlong(northwards(21_000)).size

        assertEquals(3, short)
        assertTrue(middling in (short + 1)..long)
        assertEquals(8, long)
    }

    @Test
    fun `a line with nothing to point along has no arrows`() {
        assertTrue(directionArrowsAlong(emptyList()).isEmpty())
        assertTrue(directionArrowsAlong(listOf(MapFix(51.5, -0.15))).isEmpty())
        assertTrue(directionArrowsAlong(List(5) { MapFix(51.5, -0.15) }).isEmpty())
    }

    /**
     * A course drawn across the date line heads east over it, not west round the whole world — the
     * mistake every difference of longitude in this app is guarded against.
     */
    @Test
    fun `a course over the date line still points the way it goes`() {
        val line = (0..300).map { step ->
            MapFix(-16.5, theShortWayRound(179.99 + step * 10 / 106_700.0))
        }

        directionArrowsAlong(line).forEach { assertBearing(90.0, it.bearingDegrees) }
    }

    // -- The way round a course is run (#466) ---------------------------------------------------

    private fun course(flipped: Boolean) = RouteHeader(
        id = 7L,
        name = "Willingdon Loop",
        distanceMeters = 3_500.0,
        elevationGainMeters = null,
        createdAtMillis = 0L,
        source = RouteSource.IMPORTED,
        flipped = flipped,
    )

    @Test
    fun `a course nobody flipped is run in the order its line is kept`() {
        val line = northwards(100)

        assertEquals(line, line.theWayRoundItIsRun(flipped = false))
        assertEquals(line.asReversed(), line.theWayRoundItIsRun(flipped = true))
    }

    /**
     * What the Run writes down is always against the line as kept, whichever way the runner has made
     * usual — so a flip made later never changes what an old Run says it did.
     */
    @Test
    fun `a run set out on a course says which way round its kept line it went`() {
        assertFalse(runRouteSetOutAlong(course(flipped = false), backwards = false).reversed)
        assertTrue(runRouteSetOutAlong(course(flipped = false), backwards = true).reversed)
        assertTrue(runRouteSetOutAlong(course(flipped = true), backwards = false).reversed)
        assertFalse(runRouteSetOutAlong(course(flipped = true), backwards = true).reversed)
        assertEquals(7L, runRouteSetOutAlong(course(flipped = true), backwards = false).routeId)
    }

    @Test
    fun `the page says which way the course is set to go`() {
        assertEquals(
            "Arrows show the way you'll run it — the way it was saved.",
            routeDirectionLine(flipped = false),
        )
        assertEquals(
            "Arrows show the way you'll run it — flipped from the way it was saved.",
            routeDirectionLine(flipped = true),
        )
    }
}
