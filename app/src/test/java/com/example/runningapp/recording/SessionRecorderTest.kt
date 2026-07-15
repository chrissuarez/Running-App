package com.example.runningapp.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecorderTest {

    private val fakeClock = FakeClock()

    @Test
    fun `onLocationFix measures distance from the last accepted fix, skipping over a rejected one in between`() {
        val recorder = SessionRecorder(
            clock = fakeClock,
            playSplitCue = {},
            isSplitAnnouncementsEnabled = { false },
            onMetricsUpdated = {}
        )

        // Given: first fix establishes the baseline - no distance yet.
        fakeClock.currentMillis = 0
        recorder.onLocationFix(fix(lon = 0.0, accuracy = 10f, timestampMs = 0))

        // Given: second fix is 500m away but noisy (accuracy 45m > 30m threshold) - rejected.
        // It does NOT become the new baseline - the last accepted fix stays the anchor.
        fakeClock.currentMillis = 2_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(500.0), accuracy = 45f, timestampMs = 2_000))

        // When: third fix is a further 500m away (1000m from the original baseline) and clean.
        fakeClock.currentMillis = 4_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(1_000.0), accuracy = 10f, timestampMs = 4_000))

        // Then: the full 1000m from the last accepted fix counts, not just the 500m leg from the
        // rejected fix - matching the straight line a map would draw between the two accepted
        // points once SessionRepository.getTrackPointsForMap() drops the rejected one (#38 review).
        assertEquals(1.0, recorder.getDistanceKm(), 0.001)
    }

    @Test
    fun `onLocationFix rejects a fix at 31m accuracy and accepts one at exactly 30m`() {
        val recorder = SessionRecorder(
            clock = fakeClock,
            playSplitCue = {},
            isSplitAnnouncementsEnabled = { false },
            onMetricsUpdated = {}
        )

        fakeClock.currentMillis = 0
        recorder.onLocationFix(fix(lon = 0.0, accuracy = 10f, timestampMs = 0))

        // When: next fix is 300m away with accuracy just over the 30m bar - rejected, and does
        // not become the baseline.
        fakeClock.currentMillis = 1_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(300.0), accuracy = 31f, timestampMs = 1_000))
        assertEquals(0.0, recorder.getDistanceKm(), 0.001)

        // When: next fix is a further 300m away (600m from the original baseline) with accuracy
        // exactly at the 30m bar - accepted, measured straight from the first fix.
        fakeClock.currentMillis = 2_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(600.0), accuracy = 30f, timestampMs = 2_000))
        assertEquals(0.6, recorder.getDistanceKm(), 0.001)
    }

    @Test
    fun `onLocationFix does not widen the accuracy threshold after a long gap since the last accepted fix`() {
        val recorder = SessionRecorder(
            clock = fakeClock,
            playSplitCue = {},
            isSplitAnnouncementsEnabled = { false },
            onMetricsUpdated = {}
        )

        fakeClock.currentMillis = 0
        recorder.onLocationFix(fix(lon = 0.0, accuracy = 10f, timestampMs = 0))

        // When: next fix arrives 35s later with 45m accuracy - under the old widened 250m rule
        // this would have been accepted, but the single 30m bar rejects it regardless of the gap.
        fakeClock.currentMillis = 35_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(300.0), accuracy = 45f, timestampMs = 35_000))

        assertEquals(0.0, recorder.getDistanceKm(), 0.001)
    }

    @Test
    fun `onLocationFix excludes a rejected fix from the pace window and does not fire a split cue for it`() {
        val cues = mutableListOf<String>()
        val recorder = SessionRecorder(
            clock = fakeClock,
            playSplitCue = { cues.add(it) },
            isSplitAnnouncementsEnabled = { true },
            onMetricsUpdated = {}
        )

        fakeClock.currentMillis = 0
        recorder.onLocationFix(fix(lon = 0.0, accuracy = 10f, timestampMs = 0))

        // When: a noisy fix 1200m away reports GPS speed too, but accuracy 45m fails the 30m bar -
        // it must not move distance or pace, and must not fire a split cue or become the baseline.
        fakeClock.currentMillis = 1_000
        recorder.onLocationFix(
            fix(lon = lonDegreesForMeters(1_200.0), accuracy = 45f, timestampMs = 1_000, speedMps = 5.0f)
        )

        assertEquals(0.0, recorder.getDistanceKm(), 0.001)
        assertEquals(0.0, recorder.getPaceMinPerKm(), 0.001)
        assertTrue(cues.isEmpty())

        // Then: a clean fix 2400m from the original baseline (not the rejected fix) fires the
        // cue, measured straight from the last accepted fix, skipping over the rejected one.
        fakeClock.currentMillis = 2_000
        recorder.onLocationFix(
            fix(lon = lonDegreesForMeters(2_400.0), accuracy = 10f, timestampMs = 2_000, speedMps = 5.0f)
        )

        assertEquals(2.4, recorder.getDistanceKm(), 0.001)
        assertEquals(listOf("Split 2 kilometer. Pace 6 minutes 40 seconds per kilometer."), cues)
    }

    @Test
    fun `onLocationFix prunes stale pace samples even while fixes are being rejected`() {
        val recorder = SessionRecorder(
            clock = fakeClock,
            playSplitCue = {},
            isSplitAnnouncementsEnabled = { false },
            onMetricsUpdated = {}
        )

        fakeClock.currentMillis = 0
        recorder.onLocationFix(fix(lon = 0.0, accuracy = 10f, timestampMs = 0))

        // Given: an accepted fix establishes a nonzero pace.
        fakeClock.currentMillis = 1_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(5.0), accuracy = 10f, timestampMs = 1_000, speedMps = 5.0f))
        assertTrue(recorder.getPaceMinPerKm() > 0.0)

        // When: 20s later (past the 15s pace window) only a rejected fix arrives.
        fakeClock.currentMillis = 21_000
        recorder.onLocationFix(
            fix(lon = lonDegreesForMeters(305.0), accuracy = 45f, timestampMs = 21_000, speedMps = 5.0f)
        )

        // Then: the stale pace samples still age out of the window even though this fix was
        // rejected - pruning must not be gated on acceptance, only adding a new sample is.
        assertEquals(0.0, recorder.getPaceMinPerKm(), 0.001)
    }

    @Test
    fun `getPaceMinPerKm averages GPS-reported speed over a rolling 15 second window`() {
        val recorder = SessionRecorder(
            clock = fakeClock,
            playSplitCue = {},
            isSplitAnnouncementsEnabled = { false },
            onMetricsUpdated = {}
        )

        // Given: first fix has no prior fix to derive speed from.
        fakeClock.currentMillis = 0
        recorder.onLocationFix(fix(lon = 0.0, accuracy = 10f, timestampMs = 0))

        // When: second fix, 5s later, reports 6 m/s from GPS.
        // Rolling window average = (0.0 + 6.0) / 2 = 3.0 m/s -> pace = 1000 / (3.0 * 60) min/km.
        fakeClock.currentMillis = 5_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(30.0), accuracy = 10f, timestampMs = 5_000, speedMps = 6.0f))

        assertEquals(1000.0 / (3.0 * 60.0), recorder.getPaceMinPerKm(), 0.001)
    }

    @Test
    fun `getPaceMinPerKm derives speed from distance and time when GPS speed is unavailable`() {
        val recorder = SessionRecorder(
            clock = fakeClock,
            playSplitCue = {},
            isSplitAnnouncementsEnabled = { false },
            onMetricsUpdated = {}
        )

        fakeClock.currentMillis = 0
        recorder.onLocationFix(fix(lon = 0.0, accuracy = 10f, timestampMs = 0))

        // When: second fix, 10s later, 50m away, with no GPS speed.
        // Derived speed = 50m / 10s = 5 m/s. Window average = (0.0 + 5.0) / 2 = 2.5 m/s.
        fakeClock.currentMillis = 10_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(50.0), accuracy = 10f, timestampMs = 10_000))

        assertEquals(0.05, recorder.getDistanceKm(), 0.001)
        assertEquals(1000.0 / (2.5 * 60.0), recorder.getPaceMinPerKm(), 0.001)
    }

    @Test
    fun `onLocationFix announces a split cue once per newly crossed kilometer with the current pace`() {
        val cues = mutableListOf<String>()
        val recorder = SessionRecorder(
            clock = fakeClock,
            playSplitCue = { cues.add(it) },
            isSplitAnnouncementsEnabled = { true },
            onMetricsUpdated = {}
        )

        fakeClock.currentMillis = 0
        recorder.onLocationFix(fix(lon = 0.0, accuracy = 10f, timestampMs = 0))

        // When: a single fix 1200m away, 1s later, at 5 m/s, crosses the first km split.
        // Window average = (0.0 + 5.0) / 2 = 2.5 m/s -> pace = 1000 / 150 = 6.6667 min/km
        // -> 6 min 40 sec/km.
        fakeClock.currentMillis = 1_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(1_200.0), accuracy = 10f, timestampMs = 1_000, speedMps = 5.0f))

        assertEquals(listOf("Split 1 kilometer. Pace 6 minutes 40 seconds per kilometer."), cues)
    }

    @Test
    fun `onLocationFix announces a split cue without a pace when average speed is too low to compute one`() {
        val cues = mutableListOf<String>()
        val recorder = SessionRecorder(
            clock = fakeClock,
            playSplitCue = { cues.add(it) },
            isSplitAnnouncementsEnabled = { true },
            onMetricsUpdated = {}
        )

        fakeClock.currentMillis = 0
        recorder.onLocationFix(fix(lon = 0.0, accuracy = 10f, timestampMs = 0))

        // When: a fix 1200m away arrives only 0.1s later with no GPS speed - too short a time
        // delta to derive a meaningful speed (falls back to 0.0), so the rolling average stays
        // at or below the 0.1 m/s pace floor even though the split threshold was crossed.
        fakeClock.currentMillis = 100
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(1_200.0), accuracy = 10f, timestampMs = 100))

        assertEquals(listOf("Split 1 kilometer."), cues)
    }

    @Test
    fun `onLocationFix suppresses split cues when announcements are disabled but still updates distance`() {
        val cues = mutableListOf<String>()
        val recorder = SessionRecorder(
            clock = fakeClock,
            playSplitCue = { cues.add(it) },
            isSplitAnnouncementsEnabled = { false },
            onMetricsUpdated = {}
        )

        fakeClock.currentMillis = 0
        recorder.onLocationFix(fix(lon = 0.0, accuracy = 10f, timestampMs = 0))

        fakeClock.currentMillis = 1_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(1_200.0), accuracy = 10f, timestampMs = 1_000, speedMps = 5.0f))

        assertTrue(cues.isEmpty())
        assertEquals(1.2, recorder.getDistanceKm(), 0.001)
    }

    @Test
    fun `reset clears distance, pace, and split state and emits zeroed metrics`() {
        val emitted = mutableListOf<SessionRecorderMetrics>()
        val recorder = SessionRecorder(
            clock = fakeClock,
            playSplitCue = {},
            isSplitAnnouncementsEnabled = { false },
            onMetricsUpdated = { emitted.add(it) }
        )

        fakeClock.currentMillis = 0
        recorder.onLocationFix(fix(lon = 0.0, accuracy = 10f, timestampMs = 0))
        fakeClock.currentMillis = 1_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(500.0), accuracy = 10f, timestampMs = 1_000, speedMps = 5.0f))

        recorder.reset()

        assertEquals(0.0, recorder.getDistanceKm(), 0.0)
        assertEquals(0.0, recorder.getPaceMinPerKm(), 0.0)
        assertEquals(SessionRecorderMetrics(0.0, 0.0, null), emitted.last())
    }

    @Test
    fun `discardLastFix prevents a distance jump when tracking resumes after a pause`() {
        val recorder = SessionRecorder(
            clock = fakeClock,
            playSplitCue = {},
            isSplitAnnouncementsEnabled = { false },
            onMetricsUpdated = {}
        )

        fakeClock.currentMillis = 0
        recorder.onLocationFix(fix(lon = 0.0, accuracy = 10f, timestampMs = 0))
        fakeClock.currentMillis = 1_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(100.0), accuracy = 10f, timestampMs = 1_000))
        assertEquals(0.1, recorder.getDistanceKm(), 0.001)

        // When: the session is paused (host calls discardLastFix), then a fix arrives far away
        // after a long gap - simulating the runner having moved out of GPS range while paused.
        recorder.discardLastFix()
        fakeClock.currentMillis = 100_000
        recorder.onLocationFix(fix(lon = lonDegreesForMeters(100_000.0), accuracy = 10f, timestampMs = 100_000))

        // Then: no distance is counted for that jump - the discarded fix isn't used as a baseline.
        assertEquals(0.1, recorder.getDistanceKm(), 0.001)

        // And: normal accumulation resumes from the new baseline on the next fix.
        fakeClock.currentMillis = 101_000
        recorder.onLocationFix(
            fix(lon = lonDegreesForMeters(100_000.0) + lonDegreesForMeters(50.0), accuracy = 10f, timestampMs = 101_000)
        )
        assertEquals(0.15, recorder.getDistanceKm(), 0.001)
    }

    private class FakeClock(var currentMillis: Long = 0L) : Clock {
        override fun nowMillis(): Long = currentMillis
    }

    companion object {
        // WGS84 semi-major axis: at the equator (latitude 0), the ellipsoid's cross-section is a
        // perfect circle of this radius, so a pure longitude delta there maps to distance exactly.
        private const val WGS84_SEMI_MAJOR_AXIS = 6_378_137.0

        private fun lonDegreesForMeters(meters: Double): Double = Math.toDegrees(meters / WGS84_SEMI_MAJOR_AXIS)

        private fun fix(
            lon: Double,
            accuracy: Float,
            timestampMs: Long,
            lat: Double = 0.0,
            speedMps: Float? = null,
        ) = LocationFix(
            latitude = lat,
            longitude = lon,
            accuracyMeters = accuracy,
            speedMps = speedMps,
            timestampMs = timestampMs
        )
    }
}
