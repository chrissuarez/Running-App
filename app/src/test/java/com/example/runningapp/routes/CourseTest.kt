package com.example.runningapp.routes

import com.example.runningapp.analysis.thinnedLineIndices
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.recording.METERS_PER_DEGREE
import com.example.runningapp.recording.degreesEastOf
import com.example.runningapp.recording.geodesicDistanceMeters
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Run's recorded track becomes when it is kept as a course (#55).
 *
 * Every rule here is about the line that is written down and never re-measured, so each one is
 * pinned in the JVM rather than found on a phone — the bargain [GpxRouteReader] makes on the way in.
 */
class CourseTest {

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

    /**
     * The same fix, but on the date line and on the equator, where a degree of longitude is a full
     * degree of latitude wide and east is simply east.
     *
     * Everything either side of 180° is written down as a number of the opposite sign, which is the
     * whole point of running a course through here.
     */
    private fun datelineFix(northMeters: Double, eastMeters: Double, secondsIn: Long) = TrackPoint(
        sessionId = 1,
        latitude = northMeters / metersPerDegree,
        longitude = datelineLongitude(eastMeters),
        altitudeMeters = null,
        timestampMillis = 1_700_000_000_000L + secondsIn * 1_000L,
        source = TrackPointSource.GPS,
        startsAfterPause = false,
    )

    /** Longitude this many metres east of 180°, written the way a fix would write it. */
    private fun datelineLongitude(eastMeters: Double): Double {
        val raw = 180.0 + eastMeters / metersPerDegree
        return if (raw > 180.0) raw - 360.0 else raw
    }

    /** A place on the ground, so far north and east of where the fixes above start. */
    private fun place(northMeters: Double, eastMeters: Double) = RoutePoint(
        latitude = 51.5 + northMeters / metersPerDegree,
        longitude = -0.1 + eastMeters / (metersPerDegree * 0.6225),
        elevationMeters = null,
    )

    /**
     * The line the course would have kept before there was any bound on how many places reach the
     * thinning: every place there is, laid out on the same flat sheet and walked by the same
     * thinning.
     *
     * Written out here rather than reached for in the shaping because it is the thing the bound
     * could have changed, and a test that asked the shaping what it did would agree with whatever
     * the shaping does.
     */
    private fun thinnedWithoutTheBound(points: List<RoutePoint>): List<RoutePoint> {
        val snapped = RoutePolyline.snapped(points)
        val walked = snapped.filterIndexed { i, point ->
            i == 0 || point.latitude != snapped[i - 1].latitude ||
                point.longitude != snapped[i - 1].longitude
        }
        val cosLatitude = cos(Math.toRadians(walked.first().latitude))
        val kept = thinnedLineIndices(
            x = DoubleArray(walked.size) {
                degreesEastOf(walked.first().longitude, walked[it].longitude) *
                    METERS_PER_DEGREE * cosLatitude
            },
            y = DoubleArray(walked.size) {
                (walked[it].latitude - walked.first().latitude) * METERS_PER_DEGREE
            },
            detail = 2.0,
        )
        return kept.map { walked[it] }
    }

    /**
     * A course of ordinary length is thinned from every place it has, exactly as it was before there
     * was a bound at all.
     *
     * Five thousand places is an hour and a half of running at a fix a second, comfortably above
     * anything but an ultra and comfortably below the bound, so nothing here may be shortened before
     * the thinning sees it — a bound that changed a real course's line would be a second row of
     * every course the runner had already kept.
     */
    @Test
    fun `a course of ordinary length is thinned from every place it has`() {
        var wobble = 0.11
        val ordinary = (0 until 5_000).map {
            wobble = (wobble * 7.3 + 0.61) % 1.0
            place(northMeters = it * 1.7 + wobble * 1.4, eastMeters = it * 0.9 + wobble * 2.2)
        }

        assertEquals(
            RoutePolyline.encode(thinnedWithoutTheBound(ordinary)),
            RoutePolyline.encode(courseOf(ordinary).line),
        )
    }

    /**
     * A file at the reader's own limit, shaped so that the thinning is at its worst, still becomes a
     * course.
     *
     * The thinning walk is quadratic when each split peels a place off one end and leaves the rest,
     * which is what a zigzag of steadily shrinking amplitude does: the furthest place from every
     * chord is the next one along, every time. At the 200,000 places [GpxRouteReader] will accept,
     * that is twenty billion distance measurements — an import worker pinned to a core for the rest
     * of the afternoon. Nothing shaped like this was ever run or drawn; it is what a damaged or
     * hostile file puts in front of the shaping, and the only honest proof that the bound holds is
     * that this now finishes at all.
     */
    @Test(timeout = 30_000)
    fun `a course built to be dense is thinned rather than walked for ever`() {
        val amplitude = 0.05 * 200_000
        val dense = (0 until 200_000).map {
            place(
                northMeters = it.toDouble(),
                eastMeters = (if (it % 2 == 0) 1 else -1) * (amplitude - 0.05 * it),
            )
        }

        val line = courseOf(dense).line

        // A line, with its two ends where the file's are — not the file back again.
        assertTrue(line.size >= 2)
        assertTrue(line.size <= 20_000)
        assertEquals(dense.first().latitude, line.first().latitude, 0.0000001)
        assertEquals(dense.last().latitude, line.last().latitude, 0.0000001)
    }

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
     * The one test both doors ask (#397). A lap is a course; the two shapes below are not, and the
     * file door has to turn them away for the same reason the Run door always has.
     */
    @Test
    fun `a lap holds a course and a standstill does not`() {
        val lap = listOf(
            fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = 0),
            fix(northMeters = 300.0, eastMeters = 0.0, secondsIn = 60),
            fix(northMeters = 300.0, eastMeters = 500.0, secondsIn = 120),
        )
        assertTrue(runAsCourse(lap).holdsACourse())

        // Every place the same place, so the line is one point and there is nothing to draw.
        val oneSpot = (0..9).map { fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = it.toLong()) }
        assertEquals(1, runAsCourse(oneSpot).line.size)
        assertFalse(runAsCourse(oneSpot).holdsACourse())

        // A line of real places that never reaches outside the width of a fix's own error.
        val scatter = listOf(
            fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = 0),
            fix(northMeters = 40.0, eastMeters = 0.0, secondsIn = 60),
            fix(northMeters = 0.0, eastMeters = 25.0, secondsIn = 120),
        )
        assertTrue(runAsCourse(scatter).line.size >= 2)
        assertFalse(runAsCourse(scatter).holdsACourse())
    }

    /**
     * The bar is a floor rather than a fence: a course that reaches it is kept. Pinned half a metre
     * either side of it, because which side the boundary falls on is the whole of the rule — and
     * half a metre rather than a centimetre because [RoutePolyline.snapped] rounds a place to about
     * a centimetre first, and a gap that fine could round across the bar on its own.
     */
    @Test
    fun `the bar is the width a course is kept at`() {
        fun straightLine(wideMeters: Double) = listOf(
            fix(northMeters = 0.0, eastMeters = 0.0, secondsIn = 0),
            fix(northMeters = wideMeters, eastMeters = 0.0, secondsIn = 60),
        )

        assertTrue(runAsCourse(straightLine(ROUTE_MINIMUM_METERS + 0.5)).holdsACourse())
        assertFalse(runAsCourse(straightLine(ROUTE_MINIMUM_METERS - 0.5)).holdsACourse())
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

    /**
     * The same ten minutes standing still, on the one line where a stride is written down as most of
     * the way round the world: 180° east and 180° west are the same place, so fixes wandering a few
     * metres either side of it are recorded with opposite signs and their numbers are 360 apart.
     *
     * Nothing about the ground has changed, so nothing about the answer may either. A scatter is a
     * scatter on the date line too, and if its extent were read off the raw numbers it would be a
     * course reaching halfway round the planet and would be kept.
     */
    @Test
    fun `standing still on the date line still reaches nowhere`() {
        val jitter = (0..199).map { step ->
            datelineFix(
                northMeters = if (step % 2 == 0) 8.0 else -8.0,
                eastMeters = if (step % 4 < 2) 6.0 else -6.0,
                secondsIn = step.toLong(),
            )
        }

        val course = runAsCourse(jitter)

        assertTrue(routeDistanceMeters(course.line) > 500.0)
        assertTrue(courseSpanMeters(course.line) < 40.0)
    }

    /**
     * A real course run over the date line: a quarter of a kilometre east, then a turn north. The
     * thinning lays the fixes out on a flat sheet before it decides which of them the shape needs,
     * and the sheet must be the ground the runner covered — a run laid out with a 40,000 km leap in
     * the middle of it bends everywhere and would be kept whole.
     */
    @Test
    fun `a course over the date line keeps the shape it would have anywhere else`() {
        val cornered = (0..25).map { datelineFix(0.0, -250.0 + it * 20.0, it.toLong()) } +
            (1..10).map { datelineFix(it * 20.0, 250.0, 25L + it) }

        val points = runAsCourse(cornered).line

        assertEquals(3, points.size)
        // The middle one is the corner, 250 m the far side of the line from where the run started.
        assertEquals(datelineLongitude(250.0), points[1].longitude, 0.000001)
        assertEquals(0.0, points[1].latitude, 0.000001)
    }
}
