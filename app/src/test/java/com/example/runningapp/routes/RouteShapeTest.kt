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
     * A hill is banked three metres at a time, above the last low point, rather than only at the top.
     *
     * Fifteen of the thirty metres climbed, not all thirty: the five-point window is half a kilometre
     * wide on points this far apart, and it is walked twice (#424), so the shoulders of the hill are
     * averaged off it. That is the price of smoothing a sparse file, and it is the right way to be
     * wrong — see `the jitter of a simplified track is not a hill` and
     * `a violent wobble is no longer banked as climbing`, which are what it buys.
     *
     * Fifteen rather than the twenty a single pass left. The hill is still a hill, which is the
     * whole of what this test is for: under-reporting a sparse climb by its shoulders costs metres,
     * while banking a file's own wobble reports hundreds of metres that were never climbed.
     */
    @Test
    fun `banks a climb three metres at a time`() {
        val climb = northwards(spacingMeters = 100.0, heights = listOf(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0))

        assertEquals(15.0, routeElevationGainMeters(climb)!!, 0.001)
    }

    /**
     * The defect #419 closed: a hill of a few metres is a hill, not the flat `10 m` the library used
     * to print against every route it held.
     *
     * Eight metres of climb, of which the smoothing leaves three to bank on points this far apart.
     * The number matters less than that it is neither the nought the old ten-metre rule reported for
     * a route like this nor that same `10 m` again.
     */
    @Test
    fun `banks a hill of only a few metres`() {
        val lowHill = northwards(
            spacingMeters = 100.0,
            heights = listOf(0.0, 2.0, 4.0, 6.0, 8.0, 8.0, 8.0, 6.0, 4.0, 2.0, 0.0),
        )

        assertEquals(3.08, routeElevationGainMeters(lowHill)!!, 0.001)
    }

    /**
     * The threshold itself, either side of it, on a shape the smoothing barely touches.
     *
     * A pair rather than one case, because a rise this gentle is the only way to ask the question of
     * the threshold alone: a sawtooth is flattened by the smoothing long before the threshold sees
     * it, so it would pass whatever this number were. Both are the same shape, one scaled by nine
     * tenths, and only the first clears `>=` three.
     *
     * **Flat ground either side of the rise, and that is what makes it a question about the
     * threshold rather than about the smoothing.** A centred mean leaves a straight ramp alone in
     * its middle and only rounds its two corners, so a rise with enough level ground at each end
     * still reaches its full height however many times it is smoothed — the low point stays 0 and
     * the high point stays the whole rise. Without those flats the corners meet, the rise is shaved,
     * and the test would be measuring how hard the file is smoothed. Eight level points at each end
     * is more than the five-point window reaches, so this reads the same under one pass and under
     * the two the code now makes (#424).
     */
    @Test
    fun `banks a rise that clears the threshold and not one under it`() {
        val justOver = northwards(spacingMeters = 100.0, heights = riseOf(3.0))
        val justUnder = northwards(spacingMeters = 100.0, heights = riseOf(2.7))

        assertEquals(3.0, routeElevationGainMeters(justOver)!!, 0.001)
        assertEquals(0.0, routeElevationGainMeters(justUnder)!!, 0.001)
    }

    /**
     * Level ground, a straight rise of [meters] over eight points, then level ground again — see
     * `banks a rise that clears the threshold and not one under it` for why it is shaped this way.
     */
    private fun riseOf(meters: Double): List<Double?> =
        List(8) { 0.0 } + List(8) { meters * (it + 1) / 8 } + List(8) { meters }

    /**
     * The reason smoothing is here at all: a densely sampled file jittering by eight metres a fix
     * would otherwise bank that jitter over and over, all route long. Smoothed, the same file is the
     * flat ground it describes.
     *
     * Eight metres a point rather than the twenty this asserted while the threshold was ten (#419).
     * The two numbers have to stay in step: a five-point mean leaves about a fifth of the jitter
     * behind, so a three-metre threshold covers a per-point wobble of under fifteen metres and no
     * more. Twenty is over that line and is now banked — see `a wobble the smoothing cannot absorb
     * is still banked`, which pins what this rule does not cover.
     */
    @Test
    fun `smooths away the jitter of a densely sampled file`() {
        val jittery = northwards(
            spacingMeters = 3.0,
            heights = List(60) { if (it % 2 == 0) 0.0 else 8.0 },
        )

        assertEquals(0.0, routeElevationGainMeters(jittery)!!, 0.001)
    }

    /**
     * The same jitter in a file whose points are far apart, which is what most exported tracks look
     * like: Strava and Komoot simplify the positions they export and keep the heights as recorded.
     *
     * Smoothed by the ground rule alone this would fold nothing — twenty-five metres is well past
     * the fifteen-metre window — and would bank two hundred and forty metres of climbing off flat
     * ground. The five-point rule is the whole reason it does not.
     */
    @Test
    fun `the jitter of a simplified track is not a hill`() {
        val simplified = northwards(
            spacingMeters = 25.0,
            heights = List(60) { if (it % 2 == 0) 0.0 else 8.0 },
        )

        assertEquals(0.0, routeElevationGainMeters(simplified)!!, 0.001)
    }

    /**
     * The wobble #424 closed: a file alternating by twenty metres a point over flat ground no longer
     * banks its own noise as climbing, however long the route is.
     *
     * This is the case `a wobble the smoothing cannot absorb is still banked` used to pin as a known
     * limit. A single five-point mean leaves about a fifth of the wobble behind — four metres, over
     * the three-metre threshold, banked again and again all route long, which is the #20 defect in a
     * route. The mean is applied twice ([smoothedAlong] over its own output), which leaves about a
     * twenty-fifth: under a metre, and under the threshold.
     *
     * **Asserted at two lengths, and that is the whole point.** What made the old behaviour a defect
     * was not the size of the figure but that it grew with the route: sixty points reported 118.7 m
     * and two hundred reported 398.7 m. The small figure left here is an end-of-route taper — the
     * window is shortened at the ends rather than dropping points, so the first and last few heights
     * are smoothed less — and a taper is the same two ends however much route is put between them.
     * Equal at both lengths is what says the accumulation is gone.
     */
    @Test
    fun `a violent wobble is no longer banked as climbing`() {
        val sixtyPoints = northwards(
            spacingMeters = 25.0,
            heights = List(60) { if (it % 2 == 0) 0.0 else 20.0 },
        )
        val twoHundredPoints = northwards(
            spacingMeters = 25.0,
            heights = List(200) { if (it % 2 == 0) 0.0 else 20.0 },
        )

        val short = routeElevationGainMeters(sixtyPoints)!!
        val long = routeElevationGainMeters(twoHundredPoints)!!
        assertEquals(3.556, short, 0.001)
        assertEquals(short, long, 0.001)
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
