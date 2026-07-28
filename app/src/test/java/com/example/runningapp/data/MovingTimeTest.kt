package com.example.runningapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MovingTimeTest {

    /**
     * A track laid out due north from a fixed start, one fix per second, each leg walked at the
     * given speed. Each leg is `(speedMps, seconds)` and contributes one fix per second, so the
     * track spans the summed seconds and starts with one extra fix at the origin.
     */
    private fun track(vararg legs: Pair<Double, Int>): List<TrackPoint> {
        val metresPerDegreeLatitude = 111_132.0
        var latitude = 50.7900
        var timestamp = 1_700_000_000_000L
        val points = mutableListOf(fixAt(latitude, timestamp))
        for ((speedMps, seconds) in legs) {
            repeat(seconds) {
                latitude += speedMps / metresPerDegreeLatitude
                timestamp += 1_000
                points += fixAt(latitude, timestamp)
            }
        }
        return points
    }

    private fun fixAt(latitude: Double, timestampMillis: Long) = TrackPoint(
        sessionId = 1,
        latitude = latitude,
        longitude = 0.2200,
        horizontalAccuracyMeters = 5f,
        timestampMillis = timestampMillis,
        source = TrackPointSource.GPS,
    )

    @Test
    fun `a run that never stops is moving for all of it`() {
        // 3 m/s for 600s - a shade over 5:30 /km, comfortably above the threshold throughout.
        assertEquals(600, measureMovingTimeSeconds(track(3.0 to 600)))
    }

    @Test
    fun `a long standstill is removed as rest`() {
        // 300s running, 60s stood still, 300s running: Strava drops the 60.
        assertEquals(600, measureMovingTimeSeconds(track(3.0 to 300, 0.02 to 60, 3.0 to 300)))
    }

    @Test
    fun `a brief dip below the threshold still counts as moving`() {
        // 2s slowed to a crawl - a dropped stride, a GPS wobble. Under the sustained-rest window,
        // so it stays in: stopping the clock for every hesitation is what Strava avoids.
        assertEquals(602, measureMovingTimeSeconds(track(3.0 to 300, 0.02 to 2, 3.0 to 300)))
    }

    @Test
    fun `a pause at a road crossing is rest`() {
        // 20s waiting to cross. Past the window, so it comes out - the calibration against
        // Strava's own figure for the #163 run put that window at three seconds, not fifteen.
        assertEquals(600, measureMovingTimeSeconds(track(3.0 to 300, 0.02 to 20, 3.0 to 300)))
    }

    @Test
    fun `a slow walk is moving, not rest`() {
        // 1.2 m/s is a walk - well under running pace, well over a 30-minute mile. All moving.
        assertEquals(600, measureMovingTimeSeconds(track(3.0 to 300, 1.2 to 300)))
    }

    @Test
    fun `a shuffle slower than a thirty minute mile is rest`() {
        // 0.5 m/s sustained: slower than the 0.894 m/s threshold, so it comes out.
        assertEquals(600, measureMovingTimeSeconds(track(3.0 to 300, 0.5 to 120, 3.0 to 300)))
    }

    @Test
    fun `a gap in the recording is not moving time`() {
        // Auto-pause tears the GPS stream down, leaving two fixes minutes apart in the same spot.
        // Whatever happened in between, it was not 5 minutes of running.
        val points = listOf(
            fixAt(50.7900, 1_700_000_000_000L),
            fixAt(50.7900, 1_700_000_300_000L),
        )
        assertEquals(0, measureMovingTimeSeconds(points))
    }

    @Test
    fun `a break in the recording is not moving time even when it looks fast`() {
        // A manual pause tears the GPS stream down. The runner pauses, walks 400m to a shop over 5
        // minutes and resumes: one leg, 1.33 m/s implied - comfortably "moving" by speed alone.
        // Counting it would put moving time above the run's own clock, which never banked those
        // 5 minutes at all.
        val metresPerDegreeLatitude = 111_132.0
        val points = listOf(
            fixAt(50.7900, 1_700_000_000_000L),
            fixAt(50.7900 + 400.0 / metresPerDegreeLatitude, 1_700_000_300_000L),
        )
        assertEquals(0, measureMovingTimeSeconds(points))
    }

    @Test
    fun `a run with no track has no moving time`() {
        assertEquals(0, measureMovingTimeSeconds(emptyList()))
    }

    @Test
    fun `a single fix has no moving time`() {
        assertEquals(0, measureMovingTimeSeconds(listOf(fixAt(50.7900, 1_700_000_000_000L))))
    }

    @Test
    fun `rest at the very end of a run is removed`() {
        // Standing at the finish waiting to press stop - the case that started #163.
        assertEquals(300, measureMovingTimeSeconds(track(3.0 to 300, 0.02 to 43)))
    }

    @Test
    fun `fixes arriving out of order are put back in time order`() {
        val ordered = track(3.0 to 600)
        assertEquals(measureMovingTimeSeconds(ordered), measureMovingTimeSeconds(ordered.reversed()))
    }
}
