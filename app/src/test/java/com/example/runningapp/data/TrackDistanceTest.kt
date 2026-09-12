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
        // And its seconds count, because 600 m in two minutes is a runner running (#165). The two
        // answers are the same one: the ground the leg carries is what it is judged on.
        assertEquals(120_000L, outage.movingMillis)
    }

    @Test
    fun `a leg stamped the same moment carries its ground into every measurement`() {
        // The clock did not tick between two fixes twenty metres apart. The runner covered that
        // ground and the live recorder banked it, so every reader of the track banks it too — the
        // stamp is what is wrong, not the position (#336).
        val track = script {
            running(2.0, seconds = 100)
            sameMomentJump(meters = 20.0)
            running(2.0, seconds = 100)
        }

        assertEquals(0.42, recordedKm(track), 0.005)
        assertEquals(recordedKm(track), measuredLegsKm(track), 0.001)
        assertEquals(recordedKm(track), measureTrackDistanceKm(track), 0.001)
    }

    @Test
    fun `the leg stamped the same moment carries ground but never a second`() {
        // It has no time to have been run in, so it can never be moving time and can never make a
        // pace. What it may not do is vanish from the total while its line stays on the map.
        val track = script {
            running(2.0, seconds = 60)
            sameMomentJump(meters = 20.0)
            running(2.0, seconds = 60)
        }
        val jump = measureTrack(track).theZeroTimeLeg()

        assertEquals(20.0, jump.meters, 0.5)
        assertEquals(0L, jump.millis)
        assertEquals(0L, jump.movingMillis)
        // Recorded, because there is no stretch between the two fixes to have gone unwitnessed: the
        // line is drawn across it, and the climb underneath it is still banked.
        assertTrue(jump.recorded)
    }

    @Test
    fun `a leg stamped the same moment in one place counts nothing`() {
        // The ordinary shape of a repeated stamp: the same fix delivered twice. No ground moved, so
        // counting the leg adds nothing — this rule hands out no distance of its own.
        val track = script {
            running(2.0, seconds = 60)
            sameMomentJump(meters = 0.0)
            running(2.0, seconds = 60)
        }

        assertEquals(0.24, measuredLegsKm(track), 0.005)
        assertEquals(0.0, measureTrack(track).theZeroTimeLeg().meters, 0.0)
    }

    @Test
    fun `a Pause written down beats the clock that did not tick across it`() {
        // A Pause is the one Break the Run wrote down, so it carries nothing whatever its two fixes
        // are stamped — the record beats every reading of the clock. Still a Break, so no line is
        // drawn over it either.
        val track = script {
            running(2.0, seconds = 60)
            pauseAndMoveOn(meters = 20.0, seconds = 0)
            running(2.0, seconds = 60)
        }
        val paused = measureTrack(track).theZeroTimeLeg()

        assertEquals(0.0, paused.meters, 0.0)
        assertEquals(0L, paused.movingMillis)
        assertFalse(paused.recorded)
        assertFalse(paused.carriesSpeed)
    }

    @Test
    fun `a jump stamped the same moment is counted however far it is`() {
        // No sanity check on the size of one, deliberately. The live recorder banks a fix's ground
        // with no test on the time since the last one, so a jump this size is already in the Run's
        // saved distance; a reader that refused it would put the two back into the disagreement
        // this ticket closes. What keeps a wild fix out is the accuracy gate, which both sides run
        // and neither this rule nor #336 changes.
        val track = script {
            running(2.0, seconds = 60)
            sameMomentJump(meters = 2_500.0)
            running(2.0, seconds = 60)
        }

        assertEquals(2.74, recordedKm(track), 0.01)
        assertEquals(recordedKm(track), measuredLegsKm(track), 0.001)
        assertEquals(recordedKm(track), measureTrackDistanceKm(track), 0.001)
    }

    @Test
    fun `a jump stamped the same moment neither redeems nor condemns a slow spell`() {
        // The dawdle either side of it runs to four seconds, which outlasts REST_SUSTAINED_MS, so
        // the whole spell is rest and none of it is moving time. The jump has no seconds to lend
        // the spell and no speed to judge it by, so it must leave the spell whole. Were it to break
        // the spell in two, the half after it would be two seconds — short enough to be redeemed by
        // the next moving leg — and the Run would bank movement the runner never made.
        val restedThrough = script {
            running(2.0, seconds = 30)
            running(0.2, seconds = 4)
            running(2.0, seconds = 30)
        }
        val interrupted = script {
            running(2.0, seconds = 30)
            running(0.2, seconds = 2)
            sameMomentJump(meters = 0.0)
            running(0.2, seconds = 2)
            running(2.0, seconds = 30)
        }

        // Sixty seconds of running, and not one of the four it dawdled through.
        assertEquals(60_000L, measureTrack(restedThrough).legs.sumOf { it.movingMillis })
        assertEquals(60_000L, measureTrack(interrupted).legs.sumOf { it.movingMillis })
    }

    /** The one leg of a scripted track whose two fixes share a timestamp. */
    private fun MeasuredTrack.theZeroTimeLeg(): TrackLeg = legs.single { it.millis == 0L }

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
