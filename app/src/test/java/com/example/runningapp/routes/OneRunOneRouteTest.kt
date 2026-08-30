package com.example.runningapp.routes

import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.export.GpxWriter
import com.example.runningapp.export.RunGpxTrack
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One Run makes one Route, whichever of the two doors it comes in by (#354).
 *
 * A runner can save a Run as a course on its own page (#55) *and* share it as a GPX (#84) and hand
 * that file back to the library (#54). Both doors describe the same ground, so both must write the
 * same line — the line being a Route's identity ([com.example.runningapp.data.RouteDao.keepRoute]),
 * a difference of one point between them is a second row of a course the runner already has.
 *
 * The whole trip is walked here rather than the two doors compared in the abstract: the fixes go out
 * through the real writer and come back through the real reader, so a change to the file's shape,
 * its precision, or where it is allowed to break is caught by this test and not on a phone.
 */
class OneRunOneRouteTest {

    private val startTime = 1_753_500_000_000L
    private val metersPerDegree = 111_320.0
    private val utc = ZoneId.of("UTC")

    private val session = RunnerSession(
        id = 1L,
        startTime = startTime,
        endTime = startTime + 600_000,
        durationSeconds = 600,
        avgBpm = 130,
        maxBpm = 150,
        targetZone = 2,
    )

    private fun fix(
        northMeters: Double,
        eastMeters: Double,
        secondsIn: Long,
        altitudeMeters: Double? = 10.0,
        startsAfterPause: Boolean = false,
    ) = TrackPoint(
        sessionId = 1L,
        latitude = 51.5 + northMeters / metersPerDegree,
        longitude = -0.1 + eastMeters / (metersPerDegree * 0.6225),
        altitudeMeters = altitudeMeters,
        horizontalAccuracyMeters = 5f,
        timestampMillis = startTime + secondsIn * 1_000L,
        source = TrackPointSource.GPS,
        startsAfterPause = startsAfterPause,
    )

    /** A place on the ground, so far north and east of where the fixes above start. */
    private fun place(northMeters: Double, eastMeters: Double) = RoutePoint(
        latitude = 51.5 + northMeters / metersPerDegree,
        longitude = -0.1 + eastMeters / (metersPerDegree * 0.6225),
        elevationMeters = 10.0,
    )

    /** The line the library would store for the file this Run exports. */
    private fun lineFromTheSharedFile(trackPoints: List<TrackPoint>): List<RoutePoint> {
        val gpx = GpxWriter.write(
            RunGpxTrack.build(session, trackPoints, hrSamples = emptyList(), zoneId = utc)
        )
        val read = GpxRouteReader.read(gpx.byteInputStream()) as GpxReadOutcome.Read
        return courseOf(read.points).line
    }

    /**
     * A straight road recorded once a second: the door on the Run's page keeps the two ends of it,
     * and the file must not bring back the hundred fixes in between as a course of its own.
     */
    @Test
    fun `a straight run saved and re-imported is one line`() {
        val trackPoints = (0..100).map { fix(northMeters = it * 3.0, eastMeters = 0.0, secondsIn = it.toLong()) }

        val savedOffTheRun = runAsCourse(trackPoints).line

        assertEquals(
            RoutePolyline.encode(savedOffTheRun),
            RoutePolyline.encode(lineFromTheSharedFile(trackPoints)),
        )
    }

    /** A course with real corners in it, so the agreement is not simply two points agreeing. */
    @Test
    fun `a run round corners saved and re-imported is one line`() {
        val out = (0..40).map { fix(northMeters = it * 5.0, eastMeters = 0.0, secondsIn = it.toLong()) }
        val across = (1..40).map { fix(northMeters = 200.0, eastMeters = it * 5.0, secondsIn = 40L + it) }
        val back = (1..40).map { fix(northMeters = 200.0 - it * 5.0, eastMeters = 200.0, secondsIn = 80L + it) }
        val trackPoints = out + across + back

        val savedOffTheRun = runAsCourse(trackPoints).line
        val fromTheFile = lineFromTheSharedFile(trackPoints)

        assertEquals(RoutePolyline.encode(savedOffTheRun), RoutePolyline.encode(fromTheFile))
    }

    /**
     * A track of raw fixes, wobbling the way a real one does, so the agreement is not an artefact of
     * fixes placed on tidy round numbers.
     *
     * This is the case the file's own precision could break. A GPX writes a position to seven
     * decimal places, so the file door thins places rounded to a centimetre while the Run door holds
     * the fix as it was measured — and thinning asks how far a place sits from a line, a question
     * whose answer changes in that last centimetre. Every place is moved to where the row will keep
     * it before any of it is asked ([RoutePolyline.snapped]), and this is what says so.
     */
    @Test
    fun `a wobbling run saved and re-imported is one line`() {
        // A fixed, ordinary-looking wobble rather than tidy metres: repeatable, and nowhere near a
        // round number of anything.
        var wobble = 0.0
        val trackPoints = (0..300).map {
            wobble = (wobble * 7.3 + 0.61) % 1.0
            fix(
                northMeters = it * 1.4 + wobble * 0.9,
                eastMeters = it * 0.31 + wobble * 1.7,
                secondsIn = it.toLong(),
                altitudeMeters = 10.0 + wobble * 3.0,
            )
        }

        assertEquals(
            RoutePolyline.encode(runAsCourse(trackPoints).line),
            RoutePolyline.encode(lineFromTheSharedFile(trackPoints)),
        )
    }

    /**
     * A place sitting a *fraction of a centimetre* off the two-metre line, which is the case the
     * file's own precision breaks.
     *
     * A GPX writes a position to seven decimal places, a little over a centimetre, so the same place
     * reaches the two doors as two numbers a hair apart — and thinning asks how far that place sits
     * from a line, a question whose answer changes in exactly that hair. Here the raw place is just
     * inside the two metres and would be thrown away, while the same place written down as a file
     * writes it is just outside and would be kept: one line of two points, one of three, and the
     * library holding one course twice. Snapping every place to where the row will keep it, before
     * any of it is asked, is what makes the two the same ([RoutePolyline.snapped]).
     */
    @Test
    fun `a place a hair off the line is kept or dropped the same way by both doors`() {
        val places = listOf(
            place(northMeters = 0.0, eastMeters = 0.0),
            place(northMeters = 100.0, eastMeters = 1.9995),
            place(northMeters = 200.0, eastMeters = 0.0),
        )

        assertEquals(
            RoutePolyline.encode(courseOf(places).line),
            RoutePolyline.encode(courseOf(RoutePolyline.snapped(places)).line),
        )
    }

    /**
     * Rounding a course's places before handing them over changes nothing, which is the property the
     * two doors rest on: whatever precision a course arrives at, it is thinned at the precision it
     * will be stored at.
     */
    @Test
    fun `a course is the same whether or not its places arrived rounded`() {
        var wobble = 0.3
        val places = (0..200).map {
            wobble = (wobble * 5.7 + 0.29) % 1.0
            RoutePoint(
                latitude = 51.5 + (it * 2.2 + wobble) / metersPerDegree,
                longitude = -0.1 + (it * 0.8 + wobble * 2) / (metersPerDegree * 0.6225),
                elevationMeters = 10.0,
            )
        }

        assertEquals(
            RoutePolyline.encode(courseOf(places).line),
            RoutePolyline.encode(courseOf(RoutePolyline.snapped(places)).line),
        )
    }

    /**
     * A Run with a Pause in it. The export breaks the file where the runner stopped and the reader
     * joins it back up (ADR 0014), so the two doors must still agree — the thinning being the only
     * thing that ever separated them.
     */
    @Test
    fun `a paused run saved and re-imported is one line`() {
        val before = (0..30).map { fix(northMeters = it * 4.0, eastMeters = 0.0, secondsIn = it.toLong()) }
        val after = (0..30).map {
            fix(
                northMeters = 200.0 + it * 4.0,
                eastMeters = 0.0,
                secondsIn = 400L + it,
                startsAfterPause = it == 0,
            )
        }
        val trackPoints = before + after

        assertEquals(
            RoutePolyline.encode(runAsCourse(trackPoints).line),
            RoutePolyline.encode(lineFromTheSharedFile(trackPoints)),
        )
    }
}
