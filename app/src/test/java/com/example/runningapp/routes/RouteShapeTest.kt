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
    fun measuresTheGroundBetweenTheFirstPointAndTheLast() {
        val points = listOf(
            RoutePoint(51.5000, -0.1, null),
            RoutePoint(51.5010, -0.1, null),
            RoutePoint(51.5020, -0.1, null),
        )

        assertEquals(222.4, routeDistanceMeters(points), 1.0)
    }

    @Test
    fun aCourseOfOnePointCoversNoGround() {
        assertEquals(0.0, routeDistanceMeters(listOf(RoutePoint(51.5, -0.1, null))), 0.0)
        assertEquals(0.0, routeDistanceMeters(emptyList()), 0.0)
    }

    /**
     * Points far enough apart that no window folds two together, so this is the hysteresis rule on
     * its own: every ten metres climbed above the last low point is banked, and a long hill goes on
     * banking as it is climbed rather than only at the top.
     */
    @Test
    fun banksAClimbOnceItClearsTheThreshold() {
        val climb = northwards(spacingMeters = 100.0, heights = listOf(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0))

        assertEquals(30.0, routeElevationGainMeters(climb)!!, 0.001)
    }

    /** Ten metres is the threshold a GPS-derived height has to clear (#20), so eight is noise. */
    @Test
    fun banksNothingForWobbleUnderTheThreshold() {
        val flat = northwards(spacingMeters = 100.0, heights = listOf(0.0, 4.0, 0.0, 4.0, 0.0, 4.0, 0.0))

        assertEquals(0.0, routeElevationGainMeters(flat)!!, 0.001)
    }

    /**
     * The reason smoothing is here at all: a densely sampled file jittering by twenty metres a fix
     * would otherwise bank that jitter over and over, all route long. Smoothed, the same file is the
     * flat ground it describes.
     */
    @Test
    fun smoothsAwayTheJitterOfADenselySampledFile() {
        val jittery = northwards(
            spacingMeters = 3.0,
            heights = List(60) { if (it % 2 == 0) 0.0 else 20.0 },
        )

        assertEquals(0.0, routeElevationGainMeters(jittery)!!, 0.001)
    }

    /** The same points unsmoothed are what that test is guarding against — proof it is doing work. */
    @Test
    fun theSameJitterSpreadOutIsRealGroundAndIsBanked() {
        val spacedOut = northwards(
            spacingMeters = 100.0,
            heights = List(60) { if (it % 2 == 0) 0.0 else 20.0 },
        )

        assertEquals(20.0 * 30, routeElevationGainMeters(spacedOut)!!, 0.001)
    }

    @Test
    fun hasNoElevationWhenTheFileCarriedNone() {
        assertNull(routeElevationGainMeters(northwards(100.0, List(5) { null })))
    }

    @Test
    fun hasNoElevationWhenThereIsNothingToClimbBetween() {
        assertNull(routeElevationGainMeters(listOf(RoutePoint(51.5, -0.1, 10.0))))
        assertNull(routeElevationGainMeters(emptyList()))
    }

    /** A file that states most of its heights still has a profile; the gaps take their neighbours'. */
    @Test
    fun fillsTheOddMissingHeightFromItsNeighbours() {
        val mostly = northwards(
            spacingMeters = 100.0,
            heights = listOf(null, 0.0, null, 10.0, null, 20.0, 30.0),
        )

        assertEquals(30.0, routeElevationGainMeters(mostly)!!, 0.001)
    }
}
