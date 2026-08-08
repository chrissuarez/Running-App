package com.example.runningapp.analysis

import com.example.runningapp.HrProfile
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.measureFastestEffortSeconds
import com.example.runningapp.data.measureMovingTimeSeconds
import com.example.runningapp.data.measureTrack
import com.example.runningapp.data.measureTrackDistanceKm
import com.example.runningapp.data.measureTrackRecordedSeconds
import com.example.runningapp.export.RunGpxTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Pause marked on a Run's *opening* fix changes one measurement and no other (#195).
 *
 * The recorder now writes the mark onto the first stored fix of a Run that was paused before GPS
 * had stored anything. Every reader that measures a Run reaches the mark through consecutive pairs
 * starting at the second point, so none of them ever reads the first — which is what makes it safe
 * to start writing it there. That is checked here reader by reader rather than assumed, because it
 * is the assumption the whole fix rests on.
 *
 * The one deliberate exception is [measureTrackRecordedSeconds], which rescues an interrupted Run:
 * it asks what the wait between START and the first fix was, and a Pause is the answer that stops
 * it being counted as running.
 */
class OpeningFixPauseMarkTest {

    private val run = aRun(durationSeconds = 900)
    private val profile = HrProfile(maxHr = 185, restingHr = 50)

    /** A run with everything a reader might read: hills, height, a mid-run Pause and an Outage. */
    private val track = script {
        running(speedMps = 3.0, seconds = 240, climbMeters = 20.0, barometer = true, gps = true)
        pauseAndMoveOn(meters = 5.0, seconds = 90, barometer = true, gps = true)
        running(speedMps = 3.0, seconds = 240, climbMeters = -20.0, barometer = true, gps = true)
        gap(meters = 150.0, seconds = 60, barometer = true, gps = true)
        running(speedMps = 3.0, seconds = 200, climbMeters = 5.0, barometer = true, gps = true)
    }

    private val samples = (0..890 step 10).map { aSample(run, it, bpm = 120 + it % 40) }

    /** The same run, recorded by a Run that was paused before its first fix landed. */
    private val marked: List<TrackPoint> =
        listOf(track.first().copy(startsAfterPause = true)) + track.drop(1)

    @Test
    fun `the run is worth checking with — it has a Pause, an Outage and a climb`() {
        assertTrue(track.size > 600)
        assertTrue(track.drop(1).any { it.startsAfterPause })
        assertNotNull(elevationOf(measureTrack(track)))
    }

    @Test
    fun `every leg is judged the same`() {
        assertEquals(measureTrack(track).legs, measureTrack(marked).legs)
    }

    @Test
    fun `moving time is unchanged`() {
        assertEquals(
            measureMovingTimeSeconds(track, run.durationSeconds),
            measureMovingTimeSeconds(marked, run.durationSeconds),
        )
    }

    @Test
    fun `covered distance is unchanged`() {
        assertEquals(measureTrackDistanceKm(track), measureTrackDistanceKm(marked), 0.0)
    }

    @Test
    fun `the best effort over a kilometre is unchanged`() {
        assertEquals(
            measureFastestEffortSeconds(track, targetMeters = 1_000.0),
            measureFastestEffortSeconds(marked, targetMeters = 1_000.0),
        )
    }

    @Test
    fun `the splits, the climb, the chart and the map are unchanged`() {
        assertEquals(
            groundOf(run, samples, track, profile),
            groundOf(run, samples, marked, profile),
        )
    }

    @Test
    fun `the elevation profile is unchanged`() {
        assertEquals(elevationOf(measureTrack(track)), elevationOf(measureTrack(marked)))
    }

    @Test
    fun `the history thumbnail is unchanged`() {
        assertEquals(
            routeThumbnailOf(measureTrack(track)),
            routeThumbnailOf(measureTrack(marked)),
        )
    }

    @Test
    fun `the GPX export is unchanged`() {
        assertEquals(
            RunGpxTrack.build(run, track, samples),
            RunGpxTrack.build(run, marked, samples),
        )
    }

    @Test
    fun `the rescue of an interrupted Run is the one thing that changes`() {
        // The Run's clock started a minute before its first fix landed. Without the mark that is a
        // slow satellite lock and counts as running; with it, it is a Pause and does not.
        val startedAt = track.first().timestampMillis - 60_000

        assertEquals(
            measureTrackRecordedSeconds(startedAt, track) - 60,
            measureTrackRecordedSeconds(startedAt, marked),
        )
    }
}
