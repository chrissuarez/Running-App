package com.example.runningapp.routes

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.measureTrack
import com.example.runningapp.segments.RunShape
import com.example.runningapp.segments.runShapeOf
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
 * Which saved course a Run turns out to have been on (#74).
 *
 * Scripted ground for `RunMatchingTest`'s reason: every rule here is a boundary, and a boundary
 * pinned by a recorded Run is pinned by whatever that Run happened to do. The corners below are
 * written in metres east and north of one origin. A *Run* over them is walked into fixes and
 * measured with the app's own [measureTrack]; a *course* over the same corners is written as the
 * handful of points a GPX file holds — which is the whole difference this ticket has to survive,
 * because a route builder exports a corner every few hundred metres where a phone records a fix
 * every second.
 */
class RouteRunLinkTest {

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

    /** The shape of a Run over these corners, as the app takes it off a recorded track. */
    private fun runOver(corners: List<Pair<Double, Double>>): RunShape =
        runShapeOf(
            measureTrack(
                walk(corners).mapIndexed { i, (east, north) ->
                    val fix = at(east, north)
                    TrackPoint(
                        sessionId = 1L,
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        timestampMillis = 1_000_000L + i * 5_000L,
                        source = "GPS",
                    )
                }
            )
        )!!

    /** The shape of a course drawn over these corners, as a GPX file gives them: the corners alone. */
    private fun courseOver(corners: List<Pair<Double, Double>>): RunShape = courseOrNothing(corners)!!

    /** The same, for the courses there is no shape to take of. */
    private fun courseOrNothing(corners: List<Pair<Double, Double>>): RunShape? =
        routeShapeOf(
            corners.map { (east, north) ->
                at(east, north).let { RoutePoint(it.latitude, it.longitude, null) }
            }
        )

    /** A kilometre round the block, anticlockwise from the corner of the runner's street. */
    private val theBlock = listOf(
        0.0 to 0.0,
        250.0 to 0.0,
        250.0 to 250.0,
        0.0 to 250.0,
        0.0 to 0.0,
    )

    /** Out along the main road and back the way they came — a kilometre. */
    private val outAndBack = listOf(0.0 to 0.0, 500.0 to 0.0, 0.0 to 0.0)

    /** The same block a street further out — a different route of nearly the same length. */
    private val theNextBlockOver = listOf(
        400.0 to 0.0,
        650.0 to 0.0,
        650.0 to 250.0,
        400.0 to 250.0,
        400.0 to 0.0,
    )

    // -- The rules ----------------------------------------------------------------------------

    @Test
    fun `a run over the course's ground is on the course, however sparsely the file drew it`() {
        assertTrue(runIsOnCourse(runOver(theBlock), courseOver(theBlock)))
    }

    @Test
    fun `a run somewhere else is on no course`() {
        assertFalse(runIsOnCourse(runOver(theBlock), courseOver(theNextBlockOver)))
    }

    @Test
    fun `a loop run the other way round is still that course`() {
        // The one place this parts company with matching two Runs. A course is a line with two ends
        // and no arrow on it, and the app has let a runner set out along one backwards since #56.
        assertTrue(runIsOnCourse(runOver(theBlock.reversed()), courseOver(theBlock)))
    }

    @Test
    fun `an out-and-back is that course either way, as it is for two runs`() {
        assertTrue(runIsOnCourse(runOver(outAndBack), courseOver(outAndBack)))
        assertTrue(runIsOnCourse(runOver(outAndBack.reversed()), courseOver(outAndBack)))
    }

    @Test
    fun `a course too short to hold a route has no shape at all`() {
        assertNull(courseOrNothing(listOf(0.0 to 0.0, 100.0 to 0.0)))
    }

    @Test
    fun `a course of one point has no shape at all`() {
        assertNull(courseOrNothing(listOf(0.0 to 0.0)))
    }

    @Test
    fun `the course a run is recognised on is the one it fits`() {
        val library = listOf(
            CourseShape(routeId = 7L, name = "The next block over", shape = courseOver(theNextBlockOver)),
            CourseShape(routeId = 8L, name = "Round the block", shape = courseOver(theBlock)),
        )

        assertEquals("Round the block", courseRecognising(runOver(theBlock), library)?.name)
    }

    @Test
    fun `a run on no saved course is recognised on none`() {
        val library = listOf(
            CourseShape(routeId = 7L, name = "The next block over", shape = courseOver(theNextBlockOver))
        )

        assertNull(courseRecognising(runOver(theBlock), library))
    }

    @Test
    fun `where two saved courses fit, the one closest in length wins`() {
        // The library holding the same ground twice is a real state (#402), and so is a family whose
        // two lengths differ by less than the tolerances. The runner is owed one name, not a list.
        val theSameGroundSlightlyShort =
            courseOver(listOf(0.0 to 0.0, 245.0 to 0.0, 245.0 to 245.0, 0.0 to 245.0, 0.0 to 0.0))
        val run = runOver(theBlock)
        val library = listOf(
            CourseShape(routeId = 7L, name = "Nearly the block", shape = theSameGroundSlightlyShort),
            CourseShape(routeId = 8L, name = "Round the block", shape = courseOver(theBlock)),
        )

        assertTrue(runIsOnCourse(run, theSameGroundSlightlyShort))
        assertEquals("Round the block", courseRecognising(run, library)?.name)
    }

    @Test
    fun `an exact tie on length goes to the course kept first`() {
        val library = listOf(
            CourseShape(routeId = 8L, name = "Kept second", shape = courseOver(theBlock)),
            CourseShape(routeId = 3L, name = "Kept first", shape = courseOver(theBlock)),
        )

        assertEquals("Kept first", courseRecognising(runOver(theBlock), library)?.name)
    }

    @Test
    fun `a course read the other way round keeps its length and reverses its places`() {
        val course = courseOver(theBlock)
        val backwards = course.theOtherWayRound()

        assertEquals(course.distanceMeters, backwards.distanceMeters, 0.0)
        assertEquals(course.waypoints.reversed(), backwards.waypoints)
    }
}
