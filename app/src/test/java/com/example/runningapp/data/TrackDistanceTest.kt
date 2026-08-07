package com.example.runningapp.data

import com.example.runningapp.analysis.script
import com.example.runningapp.recording.Clock
import com.example.runningapp.recording.LocationFix
import com.example.runningapp.recording.SessionRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One question — how far did this Run go? — asked of all three paths that answer it (#204).
 *
 * The live recorder banks the Run's distance as it runs, [measureTrackDistanceKm] rebuilds it for a
 * Run the rescue pass finishes, and [measureTrack]'s legs are what the Splits table and the charts
 * are cut from. A Run whose Splits do not add up to the distance printed above them is two of those
 * three disagreeing, so each test here puts one track through all of them and demands one number.
 */
class TrackDistanceTest {

    /**
     * The recorder fed the same fixes the readers are given, and asked what it banked.
     *
     * Auto-pause is left off, as it is by default. It freezes the recorder's distance without
     * writing anything onto the track, so a Run recorded with it on is a disagreement of its own —
     * one about what counts as moving, which is #165's.
     */
    private fun recordedKm(points: List<TrackPoint>): Double {
        val clock = FakeClock()
        val recorder = SessionRecorder(
            clock = clock,
            playSplitCue = {},
            isSplitAnnouncementsEnabled = { false },
            onMetricsUpdated = {},
        )
        points.forEach { point ->
            clock.currentMillis = point.timestampMillis
            // What LocationTracker does on the way into a pause: the baseline is dropped, so the
            // leg across it is never banked.
            if (point.startsAfterPause) recorder.discardLastFix()
            recorder.onLocationFix(
                LocationFix(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    accuracyMeters = point.horizontalAccuracyMeters,
                    speedMps = null,
                    timestampMs = point.timestampMillis,
                )
            )
        }
        return recorder.getDistanceKm()
    }

    private fun measuredLegsKm(points: List<TrackPoint>): Double =
        measureTrack(points).legs.sumOf { it.meters } / 1_000.0

    @Test
    fun `an outage carries its straight line into every measurement`() {
        // Two minutes in a tunnel over 600 m of ground, between two 500 m stretches of running.
        // The runner covered that ground and the recorder banked it, so the readers do too — a
        // straight line is never longer than the route, so this can only under-state the Run.
        val track = script {
            running(2.0, seconds = 250)
            gap(meters = 600.0, seconds = 120)
            running(2.0, seconds = 250)
        }

        assertEquals(1.6, recordedKm(track), 0.005)
        assertEquals(recordedKm(track), measuredLegsKm(track), 0.001)
        assertEquals(recordedKm(track), measureTrackDistanceKm(track), 0.001)
    }

    @Test
    fun `a pause carries no ground into any measurement`() {
        // The runner pauses, walks 400 m to a shop door and back to the route, and resumes. GPS is
        // torn down across a pause and the runner was not running, so all three count zero for it.
        val track = script {
            running(2.0, seconds = 250)
            pauseAndMoveOn(meters = 400.0, seconds = 300)
            running(2.0, seconds = 250)
        }

        assertEquals(1.0, recordedKm(track), 0.005)
        assertEquals(recordedKm(track), measuredLegsKm(track), 0.001)
        assertEquals(recordedKm(track), measureTrackDistanceKm(track), 0.001)
    }

    @Test
    fun `the leg across an outage carries ground but stays unrecorded`() {
        // Only the distance changes hands. The leg is still one nothing witnessed, so everything
        // reading the shape of the Run — the map, the elevation, the charts — still breaks at it.
        val track = script {
            running(2.0, seconds = 60)
            gap(meters = 600.0, seconds = 120)
            running(2.0, seconds = 60)
        }
        val outage = measureTrack(track).legs[60]

        assertEquals(600.0, outage.meters, 1.0)
        assertFalse(outage.recorded)
        // Moving time is left exactly as it was: a Break is rest until #165 says otherwise.
        assertEquals(0L, outage.movingMillis)
    }

    @Test
    fun `a Run rescued from its record measures the same as one finished live`() {
        val track = script {
            running(2.5, seconds = 400)
            gap(meters = 300.0, seconds = 45)
            running(2.5, seconds = 400)
            pauseAndMoveOn(meters = 120.0, seconds = 200)
            running(2.5, seconds = 200)
        }

        val live = recordedKm(track)
        val rescued = measureTrackDistanceKm(track)
        assertTrue("a rescued Run must not shrink: live $live, rescued $rescued", rescued > 0.0)
        assertEquals(live, rescued, 0.001)
    }

    private class FakeClock(var currentMillis: Long = 0L) : Clock {
        override fun nowMillis(): Long = currentMillis
    }
}
