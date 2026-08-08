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
    fun `an outage the runner stood still through is not moving time`() {
        // Two fixes five minutes apart in the same spot. Whatever cost the recording those
        // minutes, the runner covered no ground over them, so none of it was running.
        val points = listOf(
            fixAt(50.7900, 1_700_000_000_000L),
            fixAt(50.7900, 1_700_000_300_000L),
        )
        assertEquals(0, measureMovingTimeSeconds(points))
    }

    @Test
    fun `an outage the runner ran across is moving time`() {
        // The #165 case: a minute under dense tree cover, every fix refused by the accuracy gate,
        // 250m of ground on the far side of it. The distance total counts that leg in full, so
        // dropping its seconds is what flatters the pace.
        val metresPerDegreeLatitude = 111_132.0
        val before = track(3.0 to 300)
        val resumeLatitude = before.last().latitude + 250.0 / metresPerDegreeLatitude
        val resumeTimestamp = before.last().timestampMillis + 60_000
        assertEquals(
            300 + 60,
            measureMovingTimeSeconds(before + fixAt(resumeLatitude, resumeTimestamp)),
        )
    }

    @Test
    fun `an outage slower than a thirty minute mile is rest, like any other leg`() {
        // 400m over 5 minutes is 1.33 m_s and counts; 100m over the same 5 minutes is 0.33 m_s and
        // does not. An outage is judged on the ground it carries, by the same threshold as a leg
        // the recording covered.
        val metresPerDegreeLatitude = 111_132.0
        val before = track(3.0 to 300)
        val timestamp = before.last().timestampMillis + 300_000
        val walked = fixAt(before.last().latitude + 400.0 / metresPerDegreeLatitude, timestamp)
        val wandered = fixAt(before.last().latitude + 100.0 / metresPerDegreeLatitude, timestamp)

        assertEquals(300 + 300, measureMovingTimeSeconds(before + walked))
        assertEquals(300, measureMovingTimeSeconds(before + wandered))
    }

    @Test
    fun `a recorded pause is not moving time even when it looks fast`() {
        // The runner holds pause, walks 400m to a shop over 5 minutes and resumes: one leg, 1.33
        // m/s implied - comfortably "moving" by speed alone. The run wrote the pause down, and a
        // pause carries neither ground nor seconds however fast the two fixes either side look.
        val metresPerDegreeLatitude = 111_132.0
        val points = listOf(
            fixAt(50.7900, 1_700_000_000_000L),
            fixAt(50.7900 + 400.0 / metresPerDegreeLatitude, 1_700_000_300_000L)
                .copy(startsAfterPause = true),
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

    @Test
    fun `a short pause is rest because the run recorded it, not because the gap was long`() {
        // Pause at a shop door for 12s and walk on 20m while stopped - too short for the gap rule
        // to notice, and fast enough over the leg (1.7 m_s) to read as moving without the record.
        // The fix that resumed the run says otherwise, and that is the whole of the evidence.
        val metresPerDegreeLatitude = 111_132.0
        val before = track(3.0 to 300)
        var latitude = before.last().latitude + 20.0 / metresPerDegreeLatitude
        var timestamp = before.last().timestampMillis + 12_000
        val after = mutableListOf(fixAt(latitude, timestamp).copy(startsAfterPause = true))
        repeat(100) {
            latitude += 3.0 / metresPerDegreeLatitude
            timestamp += 1_000
            after += fixAt(latitude, timestamp)
        }

        // 300s moving, then a 12s leg the run marked as a resume, then 100s moving. Without the
        // record that leg reads as 1.7 m/s and counts, making it 412.
        assertEquals(300 + 100, measureMovingTimeSeconds(before + after))
    }

    @Test
    fun `a recorded pause shorter than the rest window is not handed back as moving`() {
        // The pause is 2s - inside REST_SUSTAINED_MS. Banked as a provisional slow spell it would
        // be restored whole by the next moving leg, so an explicit break has to be settled rest.
        val metresPerDegreeLatitude = 111_132.0
        val before = track(3.0 to 300)
        var latitude = before.last().latitude + 4.0 / metresPerDegreeLatitude
        var timestamp = before.last().timestampMillis + 2_000
        val after = mutableListOf(fixAt(latitude, timestamp).copy(startsAfterPause = true))
        repeat(100) {
            latitude += 3.0 / metresPerDegreeLatitude
            timestamp += 1_000
            after += fixAt(latitude, timestamp)
        }

        assertEquals(300 + 100, measureMovingTimeSeconds(before + after))
    }

    @Test
    fun `a run that never cleared the threshold has no moving time at all`() {
        // Started and stopped by mistake at a standstill: three fixes, two seconds, going nowhere.
        // Short enough that the sustained-rest window would otherwise keep every second of it.
        assertEquals(0, measureMovingTimeSeconds(track(0.0 to 2)))
    }
}
