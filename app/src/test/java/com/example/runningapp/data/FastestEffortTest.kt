package com.example.runningapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastestEffortTest {

    private val metresPerDegreeLatitude = 111_132.0
    private val startLatitude = 50.7900
    private val startMillis = 1_700_000_000_000L

    /**
     * A track laid out due north from a fixed start, one fix per second, each leg run at the given
     * speed. Each leg is `(speedMps, seconds)`, so the track spans the summed seconds and opens
     * with one extra fix at the origin.
     */
    private fun track(vararg legs: Pair<Double, Int>): List<TrackPoint> {
        var latitude = startLatitude
        var timestamp = startMillis
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

    /**
     * Within five seconds either way. The helper lays fixes out on a flat metres-per-degree
     * approximation while the measurement uses real geodesic distance, so a 25-minute effort is
     * expected to land a second or two off the number the helper was asked for — far too close to
     * be a warm-up, a wrong window or an untrimmed leg, which are what these tests are about.
     */
    private fun assertSeconds(expected: Long, actual: Long?) {
        assertTrue("expected about $expected, was $actual", actual != null && Math.abs(actual - expected) <= 5)
    }

    @Test
    fun `a steady 5K is measured at the pace it was run`() {
        // 3.33 m/s for 1500s: 5K in 25:00 exactly.
        assertSeconds(1500, measureFastestEffortSeconds(track(5000.0 / 1500 to 1500), FIVE_K_METERS))
    }

    @Test
    fun `a warm-up and cool-down are outside the fastest 5K, not inside it`() {
        // The case the whole thing exists for (#182): a 24-minute 5K wrapped in an 8-minute warm-up
        // walk and a 3-minute cool-down. The run's own clock says 35 minutes and would fail the
        // stage; the fastest window says 24:00 and passes it.
        val points = track(
            1.3 to 480,
            5000.0 / 1440 to 1440,
            1.3 to 180,
        )
        assertSeconds(1440, measureFastestEffortSeconds(points, FIVE_K_METERS))
    }

    @Test
    fun `a run that never covers 5K has no 5K to report`() {
        // 3 m/s for 900s is 2.7K. Absence of evidence, and the coach is told so rather than left to
        // read the run's 15 minutes as a 5K time.
        assertNull(measureFastestEffortSeconds(track(3.0 to 900), FIVE_K_METERS))
    }

    @Test
    fun `a treadmill run with no track has no 5K to report`() {
        assertNull(measureFastestEffortSeconds(emptyList(), FIVE_K_METERS))
        assertNull(measureFastestEffortSeconds(listOf(fixAt(startLatitude, startMillis)), FIVE_K_METERS))
    }

    @Test
    fun `the fastest 5K inside a longer run is the one reported`() {
        // 10K: the first half at 4:00 /km, the second at 5:00 /km. The answer is the fast half,
        // not the whole run's average and not the slow half.
        val points = track(
            5000.0 / 1200 to 1200,
            5000.0 / 1500 to 1500,
        )
        assertSeconds(1200, measureFastestEffortSeconds(points, FIVE_K_METERS))
    }

    @Test
    fun `a recorded pause costs the runner neither time nor the effort`() {
        // A 25-minute 5K with a 90s wait at a crossing in the middle, recorded as a pause. The run's
        // own clock never banked those 90s, so neither does this - and the effort survives the stop.
        val firstHalf = track(5000.0 / 1500 to 750)
        var latitude = firstHalf.last().latitude
        var timestamp = firstHalf.last().timestampMillis + 90_000
        val secondHalf = mutableListOf(fixAt(latitude, timestamp).copy(startsAfterPause = true))
        repeat(750) {
            latitude += (5000.0 / 1500) / metresPerDegreeLatitude
            timestamp += 1_000
            secondHalf += fixAt(latitude, timestamp)
        }

        assertSeconds(1500, measureFastestEffortSeconds(firstHalf + secondHalf, FIVE_K_METERS))
    }

    @Test
    fun `a gap in the recording is run through, and every second of it is charged`() {
        // Half the 5K, then five minutes with no fixes and nothing saying the run was paused, then
        // the rest. Nothing says the runner stopped, so those 300s were run seconds and all of them
        // count against the effort — the 25-minute 5K reads as 30 minutes (#204). The runner did not
        // move over the gap here, so it hands the effort no ground either.
        val firstHalf = track(5000.0 / 1500 to 750)
        var latitude = firstHalf.last().latitude
        var timestamp = firstHalf.last().timestampMillis + 300_000
        val secondHalf = mutableListOf(fixAt(latitude, timestamp))
        repeat(750) {
            latitude += (5000.0 / 1500) / metresPerDegreeLatitude
            timestamp += 1_000
            secondHalf += fixAt(latitude, timestamp)
        }

        assertSeconds(1800, measureFastestEffortSeconds(firstHalf + secondHalf, FIVE_K_METERS))
    }

    @Test
    fun `ground covered while the signal was down counts towards the effort`() {
        // The same five-minute gap, but the runner kept going through it and came out 1000 m along.
        // The straight line is credited and all 300 seconds are charged, so a Run that covered 5 km
        // in 1500s reports its 5K at 25:00 — the tunnel neither costs it the effort nor flatters it.
        val firstHalf = track(5000.0 / 1500 to 750) // 2500 m in 750s
        var latitude = firstHalf.last().latitude + 1_000.0 / metresPerDegreeLatitude
        var timestamp = firstHalf.last().timestampMillis + 300_000
        val secondHalf = mutableListOf(fixAt(latitude, timestamp))
        repeat(450) { // 1500 m more, at the same speed
            latitude += (5000.0 / 1500) / metresPerDegreeLatitude
            timestamp += 1_000
            secondHalf += fixAt(latitude, timestamp)
        }

        assertSeconds(1500, measureFastestEffortSeconds(firstHalf + secondHalf, FIVE_K_METERS))
    }

    @Test
    fun `a sparse track is trimmed to the target rather than rounded up to a whole leg`() {
        // A fix every 19s - sparse, but still inside the gap that would count as a break. 5K falls
        // at 1450s, part-way through a leg. Untrimmed, the nearest answer available is the whole
        // leg: 1463s, thirteen seconds of running the runner never did.
        var latitude = startLatitude
        var timestamp = startMillis
        val points = mutableListOf(fixAt(latitude, timestamp))
        repeat(100) {
            latitude += (5000.0 / 1450) * 19 / metresPerDegreeLatitude
            timestamp += 19_000
            points += fixAt(latitude, timestamp)
        }
        assertSeconds(1450, measureFastestEffortSeconds(points, FIVE_K_METERS))
    }

    @Test
    fun `fixes arriving out of order are put back in time order`() {
        val points = track(5000.0 / 1500 to 1500)
        assertEquals(
            measureFastestEffortSeconds(points, FIVE_K_METERS),
            measureFastestEffortSeconds(points.reversed(), FIVE_K_METERS),
        )
    }

    @Test
    fun `two fixes stamped the same second carry no distance for free`() {
        // A duplicated fix a hundred metres along would otherwise be distance covered in no time.
        val points = track(3.0 to 900).toMutableList()
        val teleport = points.last().let { it.copy(latitude = it.latitude + 2500.0 / metresPerDegreeLatitude) }
        points += teleport
        assertNull(measureFastestEffortSeconds(points, FIVE_K_METERS))
    }
}
