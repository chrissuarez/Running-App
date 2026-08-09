package com.example.runningapp.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteShapeTest {

    /**
     * A line of points running due north from Greenwich, [spacingMeters] apart, each at the height
     * the list gives. One degree of latitude is close enough to 111_320 m for a test that asserts
     * metres of climb rather than metres of ground.
     */
    private fun northwards(spacingMeters: Double, heights: List<Double?>): List<RoutePoint> =
        heights.mapIndexed { i, height ->
            RoutePoint(
                latitude = 51.5 + i * spacingMeters / 111_320.0,
                longitude = -0.1,
                elevationMeters = height,
            )
        }

    @Test
    fun `measures the ground between the first point and the last`() {
        val points = listOf(
            RoutePoint(51.5000, -0.1, null),
            RoutePoint(51.5010, -0.1, null),
            RoutePoint(51.5020, -0.1, null),
        )

        assertEquals(222.4, routeDistanceMeters(points), 1.0)
    }

    @Test
    fun `a route of one point covers no ground`() {
        assertEquals(0.0, routeDistanceMeters(listOf(RoutePoint(51.5, -0.1, null))), 0.0)
        assertEquals(0.0, routeDistanceMeters(emptyList()), 0.0)
    }

    /**
     * A hill is banked ten metres at a time, above the last low point, rather than only at the top.
     *
     * Twenty of the thirty metres climbed, not all thirty: the five-point window is half a kilometre
     * wide on points this far apart, so the shoulders of the hill are averaged off it. That is the
     * price of smoothing a sparse file, and it is the right way to be wrong — see
     * `the jitter of a simplified track is not a hill`, which is what it buys.
     */
    @Test
    fun `banks a climb ten metres at a time`() {
        val climb = northwards(spacingMeters = 100.0, heights = listOf(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0))

        assertEquals(20.0, routeElevationGainMeters(climb)!!, 0.001)
    }

    /** Ten metres is the threshold a GPS-derived height has to clear (#20), so eight is noise. */
    @Test
    fun `banks nothing for wobble under the threshold`() {
        val flat = northwards(spacingMeters = 100.0, heights = listOf(0.0, 4.0, 0.0, 4.0, 0.0, 4.0, 0.0))

        assertEquals(0.0, routeElevationGainMeters(flat)!!, 0.001)
    }

    /**
     * The reason smoothing is here at all: a densely sampled file jittering by twenty metres a fix
     * would otherwise bank that jitter over and over, all route long. Smoothed, the same file is the
     * flat ground it describes.
     */
    @Test
    fun `smooths away the jitter of a densely sampled file`() {
        val jittery = northwards(
            spacingMeters = 3.0,
            heights = List(60) { if (it % 2 == 0) 0.0 else 20.0 },
        )

        assertEquals(0.0, routeElevationGainMeters(jittery)!!, 0.001)
    }

    /**
     * The same jitter in a file whose points are far apart, which is what most exported tracks look
     * like: Strava and Komoot simplify the positions they export and keep the heights as recorded.
     *
     * Smoothed by the ground rule alone this would fold nothing — twenty-five metres is well past
     * the fifteen-metre window — and would bank hundreds of metres of climbing off flat ground. The
     * five-point rule is the whole reason it does not.
     */
    @Test
    fun `the jitter of a simplified track is not a hill`() {
        val simplified = northwards(
            spacingMeters = 25.0,
            heights = List(60) { if (it % 2 == 0) 0.0 else 20.0 },
        )

        assertEquals(0.0, routeElevationGainMeters(simplified)!!, 0.001)
    }

    @Test
    fun `has no elevation when the file carried none`() {
        assertNull(routeElevationGainMeters(northwards(100.0, List(5) { null })))
    }

    @Test
    fun `has no elevation when there is nothing to climb between`() {
        assertNull(routeElevationGainMeters(listOf(RoutePoint(51.5, -0.1, 10.0))))
        assertNull(routeElevationGainMeters(emptyList()))
    }

    /**
     * One stated height is how high one point is, not something climbed between. Spread over the
     * rest of the route it would make a flat line and report "0 m up" — telling the runner a route
     * the file said nothing about is level.
     */
    @Test
    fun `has no elevation when only one point states a height`() {
        assertNull(routeElevationGainMeters(northwards(100.0, listOf(null, null, 40.0, null, null))))
    }

    /** A file that states most of its heights still has a profile; the gaps take their neighbours'. */
    @Test
    fun `fills the odd missing height from its neighbours`() {
        val withGaps = northwards(100.0, listOf(null, 0.0, null, 10.0, null, 20.0, 30.0))
        // The same heights with nothing missing — the filling rule spelt out, so this asserts the
        // gaps were filled rather than asserting a number nobody can check by eye.
        val filledIn = northwards(100.0, listOf(0.0, 0.0, 0.0, 10.0, 10.0, 20.0, 30.0))

        assertEquals(routeElevationGainMeters(filledIn)!!, routeElevationGainMeters(withGaps)!!, 0.001)
    }
}
