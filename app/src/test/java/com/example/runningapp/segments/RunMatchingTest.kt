package com.example.runningapp.segments

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.measureTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which Runs are the same route done twice, and which only look like it (#73).
 *
 * Scripted ground for [SegmentMatchingTest]'s reason: every rule here is a boundary, and a boundary
 * pinned by a recorded Run is pinned by whatever that Run happened to do. The corners below are
 * written in metres east and north of one origin, walked into fixes, and measured with the app's own
 * [measureTrack] — so what these tests put to the matcher is what a real Run puts to it.
 */
class RunMatchingTest {

    // -- Scripting ground ---------------------------------------------------------------------

    private val originLatitude = 51.5
    private val originLongitude = -0.1

    /** A place [east] metres east and [north] metres north of the origin. */
    private fun at(east: Double, north: Double): MapFix {
        val latitudeRadians = originLatitude * PI / 180.0
        val a = 6378137.0
        val eSquared = 1.0 - (6356752.3142 / a).pow(2)
        val w = 1.0 - eSquared * sin(latitudeRadians).pow(2)
        val metersPerDegreeLatitude = PI / 180.0 * a * (1.0 - eSquared) / w.pow(1.5)
        val metersPerDegreeLongitude = PI / 180.0 * a / sqrt(w) * cos(latitudeRadians)
        return MapFix(
            latitude = originLatitude + north / metersPerDegreeLatitude,
            longitude = originLongitude + east / metersPerDegreeLongitude,
        )
    }

    /** The corners of a course, walked at [stepMeters] a fix. */
    private fun walk(
        corners: List<Pair<Double, Double>>,
        stepMeters: Double = 10.0,
    ): List<Pair<Double, Double>> {
        val walked = mutableListOf(corners.first())
        var carried = 0.0
        for (leg in 0 until corners.lastIndex) {
            val (fromEast, fromNorth) = corners[leg]
            val (toEast, toNorth) = corners[leg + 1]
            val legMeters = hypot(toEast - fromEast, toNorth - fromNorth)
            var walkedOnThisLeg = stepMeters - carried
            while (walkedOnThisLeg <= legMeters) {
                val fraction = walkedOnThisLeg / legMeters
                walked += (fromEast + (toEast - fromEast) * fraction) to
                    (fromNorth + (toNorth - fromNorth) * fraction)
                walkedOnThisLeg += stepMeters
            }
            carried = legMeters - (walkedOnThisLeg - stepMeters)
        }
        return walked
    }

    /** Those places as a Run's track, one fix every [stepMillis]. */
    private fun track(
        places: List<Pair<Double, Double>>,
        stepMillis: Long = 5_000L,
    ): List<TrackPoint> = places.mapIndexed { i, (east, north) ->
        val fix = at(east, north)
        TrackPoint(
            sessionId = 1L,
            latitude = fix.latitude,
            longitude = fix.longitude,
            timestampMillis = 1_000_000L + i * stepMillis,
            source = "GPS",
        )
    }

    /** The shape of a Run over these corners, as the app would take it. */
    private fun shapeOf(corners: List<Pair<Double, Double>>, stepMillis: Long = 5_000L): RunShape =
        runShapeOf(measureTrack(track(walk(corners), stepMillis)))!!

    /** A kilometre square, anticlockwise from the corner of the runner's street. */
    private val theBlock = listOf(
        0.0 to 0.0,
        250.0 to 0.0,
        250.0 to 250.0,
        0.0 to 250.0,
        0.0 to 0.0,
    )

    /** Out along the main road and back the way they came — a kilometre. */
    private val outAndBack = listOf(0.0 to 0.0, 500.0 to 0.0, 0.0 to 0.0)

    // -- The rules ----------------------------------------------------------------------------

    @Test
    fun `the same route run twice is one route`() {
        assertTrue(runsMatch(shapeOf(theBlock), shapeOf(theBlock, stepMillis = 7_000L)))
    }

    @Test
    fun `a route run slower is still the same route`() {
        // The whole point of spacing waypoints by distance rather than by time: an easy lap and a
        // hard one are the same ground.
        val quick = shapeOf(theBlock, stepMillis = 3_000L)
        val slow = shapeOf(theBlock, stepMillis = 12_000L)

        assertTrue(runsMatch(quick, slow))
    }

    @Test
    fun `a loop run the other way round is not the same route`() {
        val theOtherWay = theBlock.reversed()

        assertFalse(runsMatch(shapeOf(theBlock), shapeOf(theOtherWay)))
    }

    @Test
    fun `a longer run through the same start and end is not the same route`() {
        // Out to the main road and back, but round the block on the way — the same door out and the
        // same door in, and half a kilometre more of running.
        val theLongWayRound = listOf(
            0.0 to 0.0,
            500.0 to 0.0,
            500.0 to 250.0,
            0.0 to 250.0,
            0.0 to 0.0,
        )

        assertFalse(runsMatch(shapeOf(outAndBack), shapeOf(theLongWayRound)))
    }

    @Test
    fun `a different route of the same length is not the same route`() {
        val northInstead = listOf(0.0 to 0.0, 0.0 to 500.0, 0.0 to 0.0)

        assertFalse(runsMatch(shapeOf(outAndBack), shapeOf(northInstead)))
    }

    @Test
    fun `starting from the far side of the road is still the same route`() {
        val fromOverTheRoad = theBlock.map { (east, north) -> east to north }
            .toMutableList()
            .also { it[0] = 20.0 to (-20.0); it[it.lastIndex] = 20.0 to (-20.0) }

        assertTrue(runsMatch(shapeOf(theBlock), shapeOf(fromOverTheRoad)))
    }

    @Test
    fun `starting a quarter of a mile away is a different route`() {
        val fromTheNextStreet = theBlock.map { (east, north) -> east to north }
            .toMutableList()
            .also { it[0] = (-400.0) to 0.0 }

        assertFalse(runsMatch(shapeOf(theBlock), shapeOf(fromTheNextStreet)))
    }

    @Test
    fun `the totals may differ by a twentieth and no more`() {
        // The same out-and-back, turned round a little later each time. Two per cent longer still
        // matches; ten per cent longer does not.
        val aKilometre = shapeOf(outAndBack)
        val aLittleLonger = shapeOf(listOf(0.0 to 0.0, 510.0 to 0.0, 0.0 to 0.0))
        val plainlyLonger = shapeOf(listOf(0.0 to 0.0, 560.0 to 0.0, 0.0 to 0.0))

        assertTrue(runsMatch(aKilometre, aLittleLonger))
        assertFalse(runsMatch(aKilometre, plainlyLonger))
    }

    @Test
    fun `matching is the same answer whichever run is asked first`() {
        val one = shapeOf(theBlock)
        val other = shapeOf(outAndBack)

        assertEquals(runsMatch(one, other), runsMatch(other, one))
    }

    // -- What holds a shape at all --------------------------------------------------------------

    @Test
    fun `a run reduces to five places, the first its start and the last its end`() {
        val shape = shapeOf(outAndBack)

        assertEquals(RUN_SHAPE_WAYPOINTS, shape.waypoints.size)
        assertEquals(at(0.0, 0.0).latitude, shape.waypoints.first().latitude, 1e-6)
        assertEquals(at(0.0, 0.0).latitude, shape.waypoints.last().latitude, 1e-6)
        // Half way through a kilometre out-and-back is the turn, five hundred metres out.
        assertEquals(at(500.0, 0.0).longitude, shape.waypoints[2].longitude, 1e-4)
    }

    @Test
    fun `a run too short to have a route holds no shape`() {
        val roundTheCorner = walk(listOf(0.0 to 0.0, 200.0 to 0.0))

        assertNull(runShapeOf(measureTrack(track(roundTheCorner))))
    }

    @Test
    fun `a run with no track holds no shape`() {
        assertNull(runShapeOf(measureTrack(emptyList())))
    }
}
