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
        assertNull(interrupted.finishedFromRecord(samples = emptyList(), track = emptyList(), profile = profile))
    }

    @Test
    fun `the run's clock is the last second it banked, not the number of samples it saved`() {
        // 292 seconds of running, of which one had no reading — a dropout saves no row. Counting
        // rows would hand that second back and report the run a second shorter than it was.
        val dropped = samples(292).filterNot { it.elapsedSeconds == 100L }

        val finished = interrupted.finishedFromRecord(dropped, track = emptyList(), profile = profile)!!

        assertEquals(292, finished.durationSeconds)
        assertEquals(1, finished.noDataSeconds)
    }

    @Test
    fun `heart rate totals are the ones the finish would have written`() {
        // The Run banks its tally from the same rawBpm it saves on the sample, so these are
        // derived rather than estimated: a mean of 130..149 is 139, and the max is the top of it.
        val finished = interrupted.finishedFromRecord(
            samples(20, bpm = { 130 + it }),
            track = emptyList(),
            profile = profile,
        )!!

        assertEquals(139, finished.avgBpm)
        assertEquals(149, finished.maxBpm)
        assertEquals(20, finished.zone1Seconds + finished.zone2Seconds + finished.zone3Seconds +
            finished.zone4Seconds + finished.zone5Seconds)
    }

    @Test
    fun `the run ends when the recording died, not when the app noticed`() {
        val finished = interrupted.finishedFromRecord(samples(292), track = emptyList(), profile = profile)!!

        assertEquals(startedAt + 292_000, finished.endTime)
        assertTrue(finished.isFinished())
    }

    @Test
    fun `a run whose samples predate timestamps ends on its own clock`() {
        // Rows written before v16 carry no wall clock. The Run's own seconds are all there is.
        val untimed = samples(60).map { it.copy(timestampMillis = null) }

        val finished = interrupted.finishedFromRecord(untimed, track = emptyList(), profile = profile)!!

        assertEquals(startedAt + 60_000, finished.endTime)
    }

    @Test
    fun `distance comes from the stored track and the start position from its first fix`() {
        // 100 fixes a second apart, each 3 m further north: 297 m over 99 legs.
        val track = (0..99).map { fixAt(50.8152 + it * 3.0 / 111_132.0, startedAt + it * 1_000L) }

        val finished = interrupted.finishedFromRecord(samples(100), track, profile = profile)!!

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

        val finished = interrupted.finishedFromRecord(samples(10), track, profile = profile)!!

        assertEquals(0.006, finished.distanceKm, 0.001)
    }

    @Test
    fun `what the record cannot say is left as the row had it`() {
        // Walk breaks and the run/walk flag belong to the Workout, and nothing in the recording
        // says which Workout was being run. A rescue must not invent them.
        val row = interrupted.copy(isRunWalkMode = true, walkBreaksCount = 4, sessionNote = "easy")

        val finished = row.finishedFromRecord(samples(60), track = emptyList(), profile = profile)!!

        assertTrue(finished.isRunWalkMode)
        assertEquals(4, finished.walkBreaksCount)
        assertEquals("easy", finished.sessionNote)
    }
}
