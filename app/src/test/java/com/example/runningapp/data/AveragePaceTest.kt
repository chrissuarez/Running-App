package com.example.runningapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AveragePaceTest {

    @Test
    fun `pace is the whole run, not a window of it`() {
        // The run from #163: 4.53 km in 37:39. The stored column said 29.05 min/km because it
        // was the last 15 seconds of GPS speed; the run's own totals say 8:19.
        assertEquals(8.311, averagePaceMinPerKm(durationSeconds = 2259, distanceKm = 4.53), 0.001)
    }

    @Test
    fun `no distance means no pace`() {
        assertEquals(0.0, averagePaceMinPerKm(durationSeconds = 2259, distanceKm = 0.0), 0.0)
    }

    @Test
    fun `no duration means no pace`() {
        assertEquals(0.0, averagePaceMinPerKm(durationSeconds = 0, distanceKm = 4.53), 0.0)
    }

    @Test
    fun `a negative distance is treated as no distance`() {
        assertEquals(0.0, averagePaceMinPerKm(durationSeconds = 2259, distanceKm = -1.0), 0.0)
    }

    @Test
    fun `formats the run from 163 as minutes and seconds`() {
        assertEquals("8:19", formatMinutesPerKm(averagePaceMinPerKm(durationSeconds = 2259, distanceKm = 4.53)))
    }

    @Test
    fun `pads seconds below ten`() {
        // 1 km in 8:05.
        assertEquals("8:05", formatMinutesPerKm(averagePaceMinPerKm(durationSeconds = 485, distanceKm = 1.0)))
    }

    @Test
    fun `rounding up to a full minute carries into the minutes`() {
        // 10 km at 539.7 s/km - 8:59.7, which must read 9:00 and never 8:60.
        assertEquals("9:00", formatMinutesPerKm(averagePaceMinPerKm(durationSeconds = 5397, distanceKm = 10.0)))
    }

    @Test
    fun `a run with no distance has no pace to show`() {
        assertEquals("--:--", formatMinutesPerKm(averagePaceMinPerKm(durationSeconds = 2259, distanceKm = 0.0)))
    }

    // The live tile formats through this same function, so a pace can never round one way on the
    // run screen and another way in history.
    @Test
    fun `the shared formatter rounds to the nearest second`() {
        assertEquals("8:59", formatMinutesPerKm(8.98))
        assertEquals("9:00", formatMinutesPerKm(8.995))
    }

    @Test
    fun `the shared formatter has nothing to show for a zero pace`() {
        assertEquals("--:--", formatMinutesPerKm(0.0))
    }

    private fun run(durationSeconds: Long, distanceKm: Double, movingTimeSeconds: Long?) =
        RunnerSession(
            startTime = 1_700_000_000_000L,
            endTime = 1_700_000_002_259L,
            durationSeconds = durationSeconds,
            runMode = "outdoor",
            distanceKm = distanceKm,
            movingTimeSeconds = movingTimeSeconds,
        )

    @Test
    fun `a run measures its pace over moving time once it has one`() {
        // The run from #163: 4.53 km, out for 37:39, moving for 36:56. Strava showed 8:10 for the
        // same run over its own 4.52 km; the remaining second is that 10 m of distance, not the
        // clock, and closing it would mean matching Strava's GPS filtering too.
        val session = run(durationSeconds = 2259, distanceKm = 4.53, movingTimeSeconds = 2216)
        assertEquals(2216L, session.paceClockSeconds)
        assertEquals("8:09", session.averagePaceText)
    }

    @Test
    fun `a run with no moving time yet falls back to its duration`() {
        // A treadmill run, or one the backfill has not reached: 8:19 rather than nothing at all.
        val session = run(durationSeconds = 2259, distanceKm = 4.53, movingTimeSeconds = null)
        assertEquals(2259L, session.paceClockSeconds)
        assertEquals("8:19", session.averagePaceText)
    }

    @Test
    fun `a run with no distance still has no pace to show`() {
        val session = run(durationSeconds = 2259, distanceKm = 0.0, movingTimeSeconds = 2216)
        assertEquals("--:--", session.averagePaceText)
        assertEquals(0.0, session.averagePace, 0.0)
    }
}
