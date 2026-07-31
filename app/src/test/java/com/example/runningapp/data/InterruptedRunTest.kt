package com.example.runningapp.data

import com.example.runningapp.HrProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Run killed mid-recording is worth putting back (#192).
 *
 * The numbers here are checked against the run that produced the issue: a warm-up that stopped
 * recording 4m52s in, leaving 291 heart-rate samples whose last one is banked at second 292.
 */
class InterruptedRunTest {

    private val startedAt = 1_700_000_000_000L
    private val profile = HrProfile(maxHr = 185, restingHr = 50)

    private val interrupted = RunnerSession(
        id = 67,
        startTime = startedAt,
        runMode = "outdoor",
    )

    private fun samples(
        count: Int,
        bpm: (Int) -> Int = { 130 },
        firstSecond: Long = 1,
    ): List<HrSample> = (0 until count).map { i ->
        val second = firstSecond + i
        HrSample(
            sessionId = 67,
            elapsedSeconds = second,
            rawBpm = bpm(i),
            smoothedBpm = bpm(i),
            connectionState = "Connected",
            timestampMillis = startedAt + second * 1_000,
        )
    }

    private fun fixAt(latitude: Double, atMillis: Long, startsAfterPause: Boolean = false) = TrackPoint(
        sessionId = 67,
        latitude = latitude,
        longitude = 0.2373,
        horizontalAccuracyMeters = 5f,
        timestampMillis = atMillis,
        source = TrackPointSource.GPS,
        startsAfterPause = startsAfterPause,
    )

    @Test
    fun `a run with nothing recorded is left alone rather than finished as an empty run`() {
        assertNull(interrupted.finishedFromRecord(samples = emptyList(), track = emptyList(), mappedTrack = emptyList(), profile = profile))
    }

    @Test
    fun `the run's clock is the last second it banked, not the number of samples it saved`() {
        // 292 seconds of running, of which one had no reading — a dropout saves no row. Counting
        // rows would hand that second back and report the run a second shorter than it was.
        val dropped = samples(292).filterNot { it.elapsedSeconds == 100L }

        val finished = interrupted.finishedFromRecord(dropped, track = emptyList(), mappedTrack = emptyList(), profile = profile)!!

        assertEquals(292, finished.durationSeconds)
        assertEquals(1, finished.noDataSeconds)
    }

    @Test
    fun `a run recorded without a strap is as long as its track says`() {
        // Nothing banks a heart-rate second when no strap is paired, and the Run is a real one all
        // the same. The track is the only record of how long it went on for: 100 fixes a second
        // apart from the moment it started, so 99 seconds of it, every one of them without a reading.
        val track = (0..99).map { fixAt(50.8152, startedAt + it * 1_000L) }

        val finished = interrupted.finishedFromRecord(samples = emptyList(), track = track, mappedTrack = track, profile = profile)!!

        assertEquals(99, finished.durationSeconds)
        assertEquals(99, finished.noDataSeconds)
        assertEquals(0, finished.avgBpm)
    }

    @Test
    fun `a strap that drops out for good does not end the run early`() {
        // 60 seconds of readings, then the strap goes and the Run runs on for another 240 with the
        // track still being written. Trusting the last sample would rescue a 5-minute Run as a
        // 1-minute one.
        val track = (0..299).map { fixAt(50.8152, startedAt + it * 1_000L) }

        val finished = interrupted.finishedFromRecord(samples(60), track, mappedTrack = track, profile = profile)!!

        assertEquals(299, finished.durationSeconds)
        assertEquals(239, finished.noDataSeconds)
    }

    @Test
    fun `the seconds a run spent paused are not counted as time it ran`() {
        // Two fixes a second apart, a six-minute break, then two more. The Run's clock stopped for
        // the break, so the track vouches for two seconds, not six minutes and two.
        val track = listOf(
            fixAt(50.8152, startedAt),
            fixAt(50.8152, startedAt + 1_000),
            fixAt(50.8300, startedAt + 400_000, startsAfterPause = true),
            fixAt(50.8300, startedAt + 401_000),
        )

        val finished = interrupted.finishedFromRecord(samples = emptyList(), track = track, mappedTrack = track, profile = profile)!!

        assertEquals(2, finished.durationSeconds)
    }

    @Test
    fun `losing the sky is not the same as stopping`() {
        // Two minutes with no usable fix — a tunnel, a stairwell, or reception too vague for
        // getTrackPointsForMap to keep. The Run was running through all of it, and nothing marked a
        // pause, so the gap is time it counted. Only a recorded pause takes seconds off the clock.
        val track = listOf(
            fixAt(50.8152, startedAt),
            fixAt(50.8152, startedAt + 10_000),
            fixAt(50.8300, startedAt + 130_000),
            fixAt(50.8300, startedAt + 140_000),
        )

        val finished = interrupted.finishedFromRecord(samples = emptyList(), track = track, mappedTrack = track, profile = profile)!!

        assertEquals(140, finished.durationSeconds)
    }

    @Test
    fun `reception lost for the rest of the run does not end the run early`() {
        // The last two minutes are fixes too vague to draw, so they are not in the mapped track.
        // They are still the Run saying it was recording, and there is no later good fix to restore
        // those seconds through: read from the mapped track alone the Run would stop at 01:00.
        val track = (0..179).map { fixAt(50.8152, startedAt + it * 1_000L) }

        val finished = interrupted.finishedFromRecord(
            samples = emptyList(),
            track = track,
            mappedTrack = track.take(61),
            profile = profile,
        )!!

        assertEquals(179, finished.durationSeconds)
        assertEquals(startedAt + 179_000, finished.endTime)
    }

    @Test
    fun `a run whose every fix was too vague to draw is still rescued`() {
        // Nothing survives the accuracy gate, so there is no distance and no start pin to give it.
        // The Run happened all the same, and leaving it interrupted loses it for good.
        val track = (0..99).map { fixAt(50.8152, startedAt + it * 1_000L) }

        val finished = interrupted.finishedFromRecord(
            samples = emptyList(),
            track = track,
            mappedTrack = emptyList(),
            profile = profile,
        )!!

        assertEquals(99, finished.durationSeconds)
        assertEquals(0.0, finished.distanceKm, 1e-9)
        assertNull(finished.startLatitude)
    }

    @Test
    fun `the wait for a first fix is time the runner spent running`() {
        // The Run's clock starts at START; the satellites take another 30 seconds. Those seconds
        // were run, not paused, and a Run that began indoors would otherwise lose all of them.
        val track = (0..9).map { fixAt(50.8152, startedAt + 30_000 + it * 1_000L) }

        val finished = interrupted.finishedFromRecord(samples = emptyList(), track = track, mappedTrack = track, profile = profile)!!

        assertEquals(39, finished.durationSeconds)
    }

    @Test
    fun `heart rate totals are the ones the finish would have written`() {
        // The Run banks its tally from the same rawBpm it saves on the sample, so these are
        // derived rather than estimated: a mean of 130..149 is 139, and the max is the top of it.
        val finished = interrupted.finishedFromRecord(
            samples(20, bpm = { 130 + it }),
            track = emptyList(),
            mappedTrack = emptyList(),
            profile = profile,
        )!!

        assertEquals(139, finished.avgBpm)
        assertEquals(149, finished.maxBpm)
        assertEquals(20, finished.zone1Seconds + finished.zone2Seconds + finished.zone3Seconds +
            finished.zone4Seconds + finished.zone5Seconds)
    }

    @Test
    fun `the run ends when the recording died, not when the app noticed`() {
        val finished = interrupted.finishedFromRecord(samples(292), track = emptyList(), mappedTrack = emptyList(), profile = profile)!!

        assertEquals(startedAt + 292_000, finished.endTime)
        assertTrue(finished.isFinished())
    }

    @Test
    fun `a run whose samples predate timestamps ends on its own clock`() {
        // Rows written before v16 carry no wall clock. The Run's own seconds are all there is.
        val untimed = samples(60).map { it.copy(timestampMillis = null) }

        val finished = interrupted.finishedFromRecord(untimed, track = emptyList(), mappedTrack = emptyList(), profile = profile)!!

        assertEquals(startedAt + 60_000, finished.endTime)
    }

    @Test
    fun `distance comes from the stored track and the start position from its first fix`() {
        // 100 fixes a second apart, each 3 m further north: 297 m over 99 legs.
        val track = (0..99).map { fixAt(50.8152 + it * 3.0 / 111_132.0, startedAt + it * 1_000L) }

        val finished = interrupted.finishedFromRecord(samples(100), track, mappedTrack = track, profile = profile)!!

        assertEquals(0.297, finished.distanceKm, 0.002)
        assertEquals(50.8152, finished.startLatitude!!, 1e-6)
    }

    @Test
    fun `ground covered across a pause is not counted`() {
        // Two legs of 3 m either side of a resume. The runner may have walked anywhere while the
        // recording was down, so the straight line across it is not distance — the same rule the
        // map and moving time already apply.
        val track = listOf(
            fixAt(50.8152, startedAt),
            fixAt(50.8152 + 3.0 / 111_132.0, startedAt + 1_000),
            fixAt(50.8300, startedAt + 400_000, startsAfterPause = true),
            fixAt(50.8300 + 3.0 / 111_132.0, startedAt + 401_000),
        )

        val finished = interrupted.finishedFromRecord(samples(10), track, mappedTrack = track, profile = profile)!!

        assertEquals(0.006, finished.distanceKm, 0.001)
    }

    @Test
    fun `what the record cannot say is left as the row had it`() {
        // Walk breaks and the run/walk flag belong to the Workout, and nothing in the recording
        // says which Workout was being run. A rescue must not invent them.
        val row = interrupted.copy(isRunWalkMode = true, walkBreaksCount = 4, sessionNote = "easy")

        val finished = row.finishedFromRecord(samples(60), track = emptyList(), mappedTrack = emptyList(), profile = profile)!!

        assertTrue(finished.isRunWalkMode)
        assertEquals(4, finished.walkBreaksCount)
        assertEquals("easy", finished.sessionNote)
    }
}
