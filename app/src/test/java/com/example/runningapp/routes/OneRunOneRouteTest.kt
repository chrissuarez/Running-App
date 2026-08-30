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
