package com.example.runningapp.ui

import com.example.runningapp.data.RunShapeCandidate
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.measureTrack
import com.example.runningapp.data.runShapeRowOf
import com.example.runningapp.segments.runShapeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * What a Run's page says about the other times the runner ran the same route (#73).
 *
 * The geometry is [com.example.runningapp.segments.RunMatchingTest]'s; this is what the runner is
 * told about it — the number they are on, which Runs are listed, and what the trend plots.
 */
class MatchedRunModelsTest {

    private val zone = ZoneId.of("Europe/London")
    private val aDay = 24 * 60 * 60 * 1000L
    private val firstMorning = 1_700_000_000_000L

    /**
     * A Run of [east] metres out and back, on [day] days after the first, at [paceMinPerKm].
     *
     * Its shape is taken with the app's own measurement and encoded the way the row keeps it, so
     * what these tests match on is what the database would hand the page.
     */
    private fun aRun(
        id: Long,
        day: Long,
        east: Double = 500.0,
        paceMinPerKm: Double = 6.0,
    ): RunShapeCandidate {
        val row = runShapeRowOf(id, runShapeOf(measureTrack(outAndBack(id, east)))!!)
        return RunShapeCandidate(
            sessionId = id,
            shape = row.shape!!,
            distanceMeters = row.distanceMeters,
            startTime = firstMorning + day * aDay,
            ranAtUtcOffsetSeconds = 0,
            durationSeconds = 1_800L,
            movingTimeSeconds = 1_700L,
            avgPaceMinPerKm = paceMinPerKm,
        )
    }

    private fun outAndBack(id: Long, east: Double): List<TrackPoint> {
        val metersPerDegreeLongitude = 69_000.0
        val places = buildList {
            var out = 0.0
            while (out <= east) { add(out); out += 10.0 }
            var back = east
            while (back >= 0.0) { add(back); back -= 10.0 }
        }
        return places.mapIndexed { i, meters ->
            TrackPoint(
                sessionId = id,
                latitude = 51.5,
                longitude = -0.1 + meters / metersPerDegreeLongitude,
                timestampMillis = firstMorning + i * 5_000L,
                source = "GPS",
            )
        }
    }

    @Test
    fun `a run nobody has repeated has no group`() {
        assertNull(matchedRunsUi(listOf(aRun(1L, day = 0)), sessionId = 1L, zone = zone))
    }

    @Test
    fun `a run the app has never shaped has no group`() {
        assertNull(matchedRunsUi(listOf(aRun(1L, day = 0)), sessionId = 99L, zone = zone))
    }

    @Test
    fun `the same route run three times is a group of three, oldest first`() {
        val group = matchedRunsUi(
            listOf(aRun(3L, day = 20), aRun(1L, day = 0), aRun(2L, day = 7)),
            sessionId = 2L,
            zone = zone,
        )!!

        assertEquals(listOf(1L, 2L, 3L), group.runs.map { it.sessionId })
        assertEquals(3, group.count)
    }

    @Test
    fun `the run being looked at is the one the page counts to`() {
        val group = matchedRunsUi(
            listOf(aRun(1L, day = 0), aRun(2L, day = 7), aRun(3L, day = 20)),
            sessionId = 2L,
            zone = zone,
        )!!

        assertEquals(2, group.position)
        assertEquals("Your 2nd run on this route", matchedRunHeadline(group.position))
        assertTrue(group.runs.single { it.isThisRun }.sessionId == 2L)
    }

    @Test
    fun `a run over different ground is not in the group`() {
        val group = matchedRunsUi(
            listOf(aRun(1L, day = 0), aRun(2L, day = 3), aRun(3L, day = 5, east = 2_000.0)),
            sessionId = 1L,
            zone = zone,
        )!!

        assertEquals(listOf(1L, 2L), group.runs.map { it.sessionId })
    }

    @Test
    fun `the ordinal is the one a runner would say`() {
        assertEquals("Your 1st run on this route", matchedRunHeadline(1))
        assertEquals("Your 3rd run on this route", matchedRunHeadline(3))
        assertEquals("Your 11th run on this route", matchedRunHeadline(11))
        assertEquals("Your 13th run on this route", matchedRunHeadline(13))
        assertEquals("Your 21st run on this route", matchedRunHeadline(21))
        assertEquals("Your 102nd run on this route", matchedRunHeadline(102))
    }

    @Test
    fun `the count says how many have gone this way`() {
        assertEquals("2 runs on this route", matchedRunCountLabel(2))
        assertEquals("14 runs on this route", matchedRunCountLabel(14))
    }

    // -- The trend ------------------------------------------------------------------------------

    @Test
    fun `the trend is one point a day, placed on the calendar`() {
        val group = matchedRunsUi(
            listOf(aRun(1L, day = 0), aRun(2L, day = 14)),
            sessionId = 1L,
            zone = zone,
        )!!

        val trend = matchedRunTrendPoints(group.runs)

        assertEquals(listOf(0, 14), trend.map { it.dayOffset })
    }

    @Test
    fun `two runs on one day leave one point, the quicker of them`() {
        val group = matchedRunsUi(
            listOf(
                aRun(1L, day = 0, paceMinPerKm = 6.0),
                aRun(2L, day = 0, paceMinPerKm = 5.5),
                aRun(3L, day = 7, paceMinPerKm = 5.8),
            ),
            sessionId = 1L,
            zone = zone,
        )!!

        val trend = matchedRunTrendPoints(group.runs)

        assertEquals(2, trend.size)
        assertEquals(2L, trend.first().sessionId)
        assertEquals(3, group.count)
    }

    @Test
    fun `one day of running is no trend at all`() {
        val group = matchedRunsUi(
            listOf(aRun(1L, day = 0, paceMinPerKm = 6.0), aRun(2L, day = 0, paceMinPerKm = 5.5)),
            sessionId = 1L,
            zone = zone,
        )!!

        assertTrue(matchedRunTrendPoints(group.runs).isEmpty())
    }

    @Test
    fun `a run with no measured pace is listed but not plotted`() {
        val group = matchedRunsUi(
            listOf(
                aRun(1L, day = 0, paceMinPerKm = 0.0),
                aRun(2L, day = 7, paceMinPerKm = 5.5),
                aRun(3L, day = 14, paceMinPerKm = 5.4),
            ),
            sessionId = 1L,
            zone = zone,
        )!!

        val trend = matchedRunTrendPoints(group.runs)

        assertEquals(3, group.count)
        assertEquals(listOf(2L, 3L), trend.map { it.sessionId })
        assertEquals("--:-- /km", group.runs.first().paceLabel)
        assertFalse(trend.any { it.paceMinPerKm <= 0.0 })
    }

    @Test
    fun `the chart is read out as the quickest pace at each end of it`() {
        val group = matchedRunsUi(
            listOf(aRun(1L, day = 0, paceMinPerKm = 6.0), aRun(2L, day = 7, paceMinPerKm = 5.5)),
            sessionId = 1L,
            zone = zone,
        )!!

        val spoken = matchedRunTrendDescription(matchedRunTrendPoints(group.runs))!!

        assertTrue(spoken, spoken.contains("6:00 /km on the first day"))
        assertTrue(spoken, spoken.contains("5:30 /km on the latest"))
    }

    @Test
    fun `nothing plotted is nothing to read out`() {
        assertNull(matchedRunTrendDescription(emptyList()))
    }
}
