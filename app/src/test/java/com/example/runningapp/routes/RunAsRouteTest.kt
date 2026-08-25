package com.example.runningapp.routes

import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.recording.geodesicDistanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Run's recorded track becomes when it is kept as a course (#55).
 *
 * Every rule here is about the line that is written down and never re-measured, so each one is
 * pinned in the JVM rather than found on a phone — the bargain [GpxRouteReader] makes on the way in.
 */
class RunAsRouteTest {

    /** A degree of latitude is about this many metres, which is how the spacings below are chosen. */
    private val metersPerDegree = 111_320.0

    private fun fix(
        northMeters: Double,
        eastMeters: Double,
        secondsIn: Long,
        altitudeMeters: Double? = null,
        startsAfterPause: Boolean = false,
        source: String = TrackPointSource.GPS,
    ) = TrackPoint(
        sessionId = 1,
        latitude = 51.5 + northMeters / metersPerDegree,
        // A degree of longitude is shorter this far north; cos(51.5°) is about 0.6225.
        longitude = -0.1 + eastMeters / (metersPerDegree * 0.6225),
        altitudeMeters = altitudeMeters,
        timestampMillis = 1_700_000_000_000L + secondsIn * 1_000L,
        source = source,
        startsAfterPause = startsAfterPause,
    )

    @Test
    fun `the course is walked in the order the run recorded it`() {
        val outOfOrder = listOf(
            fix(northMeters = 200.0, eastMeters = 0.0, secondsIn = 60),
            fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = 0),
            fix(northMeters = 200.0, eastMeters = 200.0, secondsIn = 120),
        )

        val points = runAsCourse(outOfOrder).line

        assertEquals(3, points.size)
        assertEquals(51.5, points.first().latitude, 0.000001)
        assertEquals(200.0, geodesicDistanceMeters(
            points[0].latitude, points[0].longitude, points[1].latitude, points[1].longitude
        ), 1.0)
    }

    @Test
    fun `standing on one spot is one point, not a hundred`() {
        val stoodStill = (0..99).map { fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = it.toLong()) }

        assertEquals(1, runAsCourse(stoodStill).line.size)
    }

    @Test
    fun `a straight road is its two ends`() {
        val straight = (0..50).map { fix(northMeters = it * 10.0, eastMeters = 0.0, secondsIn = it.toLong()) }

        assertEquals(2, runAsCourse(straight).line.size)
    }

    @Test
    fun `the corner the run turned survives`() {
        val turned = (0..20).map { fix(northMeters = it * 10.0, eastMeters = 0.0, secondsIn = it.toLong()) } +
            (1..20).map { fix(northMeters = 200.0, eastMeters = it * 10.0, secondsIn = 20L + it) }

        val points = runAsCourse(turned).line

        assertEquals(3, points.size)
        // The middle one is the corner itself, where the run stopped going north.
        assertEquals(200.0, (points[1].latitude - 51.5) * metersPerDegree, 1.0)
    }

    @Test
    fun `a wobble finer than the course's detail is not a bend`() {
        val wobbled = listOf(
            fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = 0),
            fix(northMeters = 100.0, eastMeters = 1.0, secondsIn = 20),
            fix(northMeters = 200.0, eastMeters = 0.0, secondsIn = 40),
        )

        assertEquals(2, runAsCourse(wobbled).line.size)
    }

    @Test
    fun `a step aside the course can show is kept`() {
        val steppedAside = listOf(
            fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = 0),
            fix(northMeters = 100.0, eastMeters = 40.0, secondsIn = 20),
            fix(northMeters = 200.0, eastMeters = 0.0, secondsIn = 40),
        )

        assertEquals(3, runAsCourse(steppedAside).line.size)
    }

    @Test
    fun `the heights the run recorded come with the course`() {
        val overAHill = listOf(
            fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = 0, altitudeMeters = 10.0),
            fix(northMeters = 100.0, eastMeters = 40.0, secondsIn = 20, altitudeMeters = 40.0),
            fix(northMeters = 200.0, eastMeters = 0.0, secondsIn = 40, altitudeMeters = 12.0),
        )

        assertEquals(listOf(10.0, 40.0, 12.0), runAsCourse(overAHill).line.map { it.elevationMeters })
    }

    @Test
    fun `a run with no heights in it carries none`() {
        val flat = listOf(
            fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = 0),
            fix(northMeters = 200.0, eastMeters = 0.0, secondsIn = 40),
        )

        assertNull(routeElevationGainMeters(runAsCourse(flat).asRecorded))
    }

    /**
     * A Route has no Breaks: the stretches a recording arrives in are joined, exactly as the
     * segments of a GPX file are ([GpxRouteReader], ADR 0014). The runner is keeping the course they
     * went round, not a record of where they stood still in the middle of it.
     */
    @Test
    fun `a pause is joined rather than ending the course`() {
        val paused = listOf(
            fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = 0),
            fix(northMeters = 200.0, eastMeters = 0.0, secondsIn = 40),
            fix(northMeters = 200.0, eastMeters = 200.0, secondsIn = 400, startsAfterPause = true),
            fix(northMeters = 400.0, eastMeters = 200.0, secondsIn = 440),
        )

        assertEquals(4, runAsCourse(paused).line.size)
    }

    /**
     * The runs from before the app recorded a track of its own, whose fixes were rescued from the
     * heart-rate breadcrumbs and sit minutes rather than seconds apart (#37). Nothing here may
     * mistake that sparseness for a straight road.
     */
    @Test
    fun `a run backfilled from breadcrumbs still makes a usable course`() {
        val breadcrumbs = listOf(
            fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = 0, source = TrackPointSource.BACKFILL),
            fix(northMeters = 500.0, eastMeters = 0.0, secondsIn = 180, source = TrackPointSource.BACKFILL),
            fix(northMeters = 500.0, eastMeters = 500.0, secondsIn = 360, source = TrackPointSource.BACKFILL),
            fix(northMeters = 0.0, eastMeters = 500.0, secondsIn = 540, source = TrackPointSource.BACKFILL),
        )

        val points = runAsCourse(breadcrumbs).line

        assertEquals(4, points.size)
        assertTrue(routeDistanceMeters(points) > 1_400.0)
    }

    @Test
    fun `a run that recorded nothing is no course at all`() {
        assertEquals(emptyList<RoutePoint>(), runAsCourse(emptyList()).line)
    }

    /**
     * A hill is not a bend. A road straight up one side of it and down the other is two points once
     * the line is thinned, and the crest — the whole of the climb — is one of the points thrown
     * away, so the heights are read off what the Run recorded rather than off what was kept.
     */
    @Test
    fun `the climb is still there after the straight road it is on has been thinned`() {
        val overAHill = (0..40).map { step ->
            fix(
                northMeters = step * 25.0,
                eastMeters = 0.0,
                secondsIn = step.toLong() * 10,
                altitudeMeters = 10.0 + if (step <= 20) step * 5.0 else (40 - step) * 5.0,
            )
        }

        val course = runAsCourse(overAHill)

        assertEquals(2, course.line.size)
        assertEquals(41, course.asRecorded.size)
        // Not the whole hundred metres: the smoothing shaves the shoulders off a hill whose
        // points sit far apart, which is the bargain RouteShape argues for. What matters is that
        // the climb is still a climb rather than the nought the thinned line would report.
        assertTrue(routeElevationGainMeters(course.asRecorded)!! > 70.0)
        assertEquals(0.0, routeElevationGainMeters(course.line) ?: 0.0, 0.001)
    }

    @Test
    fun `a lap reaches as far across the ground as its widest side`() {
        val lap = listOf(
            fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = 0),
            fix(northMeters = 300.0, eastMeters = 0.0, secondsIn = 60),
            fix(northMeters = 300.0, eastMeters = 500.0, secondsIn = 120),
            fix(northMeters = 0.0, eastMeters = 500.0, secondsIn = 180),
        )

        assertEquals(500.0, courseSpanMeters(runAsCourse(lap).line), 2.0)
    }

    /**
     * Ten minutes standing on one spot is hundreds of metres of wandering and no course at all, so
     * how far the line *goes* cannot be what decides it.
     */
    @Test
    fun `standing still wanders a long way and reaches nowhere`() {
        val jitter = (0..199).map { step ->
            fix(
                northMeters = if (step % 2 == 0) 8.0 else -8.0,
                eastMeters = if (step % 4 < 2) 6.0 else -6.0,
                secondsIn = step.toLong(),
            )
        }

        val course = runAsCourse(jitter)

        assertTrue(routeDistanceMeters(course.line) > 500.0)
        assertTrue(courseSpanMeters(course.line) < 40.0)
    }
}
