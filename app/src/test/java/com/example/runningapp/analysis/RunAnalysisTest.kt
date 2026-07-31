package com.example.runningapp.analysis

import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scripted Runs through the one seam the detail page draws from (#44).
 *
 * Every test states a Run in the terms a runner would — "the strap dropped out for a minute", "a
 * treadmill Run", "a Run from before the app recorded GPS" — and asserts on what the page can then
 * draw, never on how the module got there.
 */
class RunAnalysisTest {

    @Test
    fun `a run's heart rate is drawn over its elapsed clock`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 180),
            samples = samples(0 to 120, 1 to 130, 2 to 140, 3 to 125)
        )

        val chart = requireNotNull(analysis.chart)
        assertEquals(
            listOf(
                HeartRateReading(0L, 120),
                HeartRateReading(1L, 130),
                HeartRateReading(2L, 140),
                HeartRateReading(3L, 125)
            ),
            chart.heartRate.single().readings
        )
        assertEquals(180L, chart.elapsedSecondsSpan)
    }

    @Test
    fun `readings are charted in the run's own order, whatever order they arrive in`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 120),
            samples = samples(2 to 140, 0 to 120, 1 to 130)
        )

        assertEquals(
            listOf(0L, 1L, 2L),
            requireNotNull(analysis.chart).heartRate.single().readings.map { it.elapsedSeconds }
        )
    }

    @Test
    fun `a treadmill run charts the same as an outdoor one`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 120, runMode = "treadmill"),
            samples = samples(0 to 110, 1 to 120, 2 to 130)
        )

        assertEquals(3, requireNotNull(analysis.chart).heartRate.single().readings.size)
    }

    @Test
    fun `a run with no heart rate at all has nothing to chart`() {
        assertNull(RunAnalysis.of(aRun(durationSeconds = 600), emptyList()).chart)
    }

    @Test
    fun `a run whose strap never connected has nothing to chart`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 3),
            samples = samples(0 to 0, 1 to 0, 2 to 0, 3 to 0)
        )

        assertNull(analysis.chart)
    }

    @Test
    fun `a row with no beat in it is a hole, not a heart rate of zero`() {
        // No recorder has ever written such a row, but if one is ever found it must break the line
        // where it sits rather than drag it to the floor.
        val lost = (2L..60L).map { it to 0 }
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 62),
            samples = samples(0 to 120, 1 to 122, *lost.toTypedArray(), 61 to 130, 62 to 132)
        )

        val chart = requireNotNull(analysis.chart)
        assertEquals(2, chart.heartRate.size)
        assertEquals(listOf(HeartRateReading(0L, 120), HeartRateReading(1L, 122)), chart.heartRate[0].readings)
        assertEquals(listOf(HeartRateReading(61L, 130), HeartRateReading(62L, 132)), chart.heartRate[1].readings)
        assertNull(chart.readingAt(30L))
    }

    @Test
    fun `a short dropout in a second-by-second recording breaks the line too`() {
        // A run recorded today banks a row for every second it had a beat for and no row at all for
        // the seconds the strap was lost in. Twenty of those in a row is a real hole, and drawing a
        // line across it would invent twenty seconds of heart rate and let the readout report one.
        val beforeAndAfter = ((0L..20L) + (41L..60L)).map { it to 130 }
        val chart = requireNotNull(
            RunAnalysis.of(
                run = aRun(durationSeconds = 60),
                samples = samples(*beforeAndAfter.toTypedArray())
            ).chart
        )

        assertEquals(2, chart.heartRate.size)
        assertEquals(20L, chart.heartRate[0].readings.last().elapsedSeconds)
        assertEquals(41L, chart.heartRate[1].readings.first().elapsedSeconds)
        assertNull(chart.readingAt(30L))
    }

    @Test
    fun `a single missing second is a hole like any other`() {
        // Every run in the history wrote a row for each second it had a beat for, so second 2 has no
        // row for one reason only: the strap was lost in it.
        val chart = requireNotNull(
            RunAnalysis.of(
                run = aRun(durationSeconds = 4),
                samples = samples(0 to 120, 1 to 121, 3 to 130, 4 to 131)
            ).chart
        )

        assertEquals(2, chart.heartRate.size)
        assertNull(chart.readingAt(2L))
    }

    @Test
    fun `readings a second apart are one unbroken line`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 30),
            samples = samples(*(0L..30L).map { it to 120 }.toTypedArray())
        )

        assertEquals(1, requireNotNull(analysis.chart).heartRate.size)
    }

    @Test
    fun `a long silence in the recording breaks the line`() {
        val recorded = ((0L..30L) + (330L..360L)).map { it to 124 }
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 400),
            samples = samples(*recorded.toTypedArray())
        )

        assertEquals(2, requireNotNull(analysis.chart).heartRate.size)
    }

    @Test
    fun `the chart spans the whole run even when the strap gave up early`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 1800),
            samples = samples(0 to 120, 60 to 130)
        )

        assertEquals(1800L, requireNotNull(analysis.chart).elapsedSecondsSpan)
    }

    @Test
    fun `the chart spans the readings when the run banked no duration of its own`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 0),
            samples = samples(0 to 120, 240 to 130)
        )

        assertEquals(240L, requireNotNull(analysis.chart).elapsedSecondsSpan)
    }

    @Test
    fun `the beats-per-minute scale is rounded outwards to tens`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 120),
            samples = samples(0 to 118, 60 to 143)
        )

        val chart = requireNotNull(analysis.chart)
        assertEquals(110, chart.bpmFloor)
        assertEquals(150, chart.bpmCeiling)
    }

    @Test
    fun `the beats-per-minute scale never runs off the ends of a plausible heart rate`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 120),
            samples = samples(0 to 41, 60 to 219)
        )

        val chart = requireNotNull(analysis.chart)
        assertEquals(40, chart.bpmFloor)
        assertEquals(220, chart.bpmCeiling)
    }

    @Test
    fun `a run of nothing but strap glitches still gets a scale that runs upwards`() {
        // Nothing sanity-checks a beat on its way into the recording, so a Strap that spent a run
        // reporting numbers no heart produces reaches the chart as it was recorded. Held to the
        // plausible ends, the scale must still run from a floor upwards to a ceiling.
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 120),
            samples = samples(0 to 250, 60 to 255)
        )

        val chart = requireNotNull(analysis.chart)
        assertTrue(chart.bpmCeiling > chart.bpmFloor)
        assertEquals(220, chart.bpmCeiling)
    }

    @Test
    fun `a flat run still gets a scale with room in it`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 120),
            samples = samples(0 to 130, 60 to 130)
        )

        val chart = requireNotNull(analysis.chart)
        assertTrue(chart.bpmCeiling > chart.bpmFloor)
    }

    @Test
    fun `dragging across the chart reads out the beat nearest the finger`() {
        val chart = requireNotNull(
            RunAnalysis.of(
                run = aRun(durationSeconds = 10),
                samples = samples(*(0L..10L).map { it to (120 + it.toInt()) }.toTypedArray())
            ).chart
        )

        assertEquals(HeartRateReading(6L, 126), chart.readingAt(6L))
        assertEquals(HeartRateReading(10L, 130), chart.readingAt(10L))
        // Just off either end of the whole recording, which is where a finger dragged to the edge
        // of the chart lands.
        assertEquals(HeartRateReading(0L, 120), chart.readingAt(-5L))
        assertEquals(HeartRateReading(10L, 130), chart.readingAt(14L))
    }

    @Test
    fun `dragging into a stretch the strap missed reads out nothing`() {
        val recorded = ((0L..5L) + (75L..80L)).map { it to 122 }
        val chart = requireNotNull(
            RunAnalysis.of(
                run = aRun(durationSeconds = 80),
                samples = samples(*recorded.toTypedArray())
            ).chart
        )

        assertNull(chart.readingAt(40L))
    }

    @Test
    fun `dragging into a break too short to be wider than the finger still reads out nothing`() {
        // Seconds 2 to 4 were never recorded, so the line breaks there — and every one of those
        // seconds is within a finger's reach of a reading on either side. The readout must agree
        // with the break the runner can see rather than hand back a beat from beside it.
        val chart = requireNotNull(
            RunAnalysis.of(
                run = aRun(durationSeconds = 10),
                samples = samples(0 to 120, 1 to 121, 5 to 130, 6 to 131, 7 to 132)
            ).chart
        )

        assertEquals(2, chart.heartRate.size)
        assertNull(chart.readingAt(2L))
        assertNull(chart.readingAt(3L))
        assertNull(chart.readingAt(4L))
        // The outer ends of the recording are still forgiving, which is what that tolerance is for.
        assertEquals(HeartRateReading(0L, 120), chart.readingAt(-2L))
        assertEquals(HeartRateReading(7L, 132), chart.readingAt(10L))
    }

    @Test
    fun `a run is titled by the part of the day it was run in`() {
        assertEquals("Morning Run", runHeadline(aRunStartedAt("2026-07-31T07:15"), ZONE))
        assertEquals("Afternoon Run", runHeadline(aRunStartedAt("2026-07-31T13:00"), ZONE))
        assertEquals("Evening Run", runHeadline(aRunStartedAt("2026-07-31T19:45"), ZONE))
        assertEquals("Night Run", runHeadline(aRunStartedAt("2026-07-31T23:30"), ZONE))
        assertEquals("Night Run", runHeadline(aRunStartedAt("2026-07-31T04:00"), ZONE))
    }

    @Test
    fun `a treadmill run says so in its title`() {
        assertEquals(
            "Morning Treadmill Run",
            runHeadline(aRunStartedAt("2026-07-31T07:15", runMode = "treadmill"), ZONE)
        )
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/London")

        fun aRun(durationSeconds: Long, runMode: String = "outdoor") = RunnerSession(
            id = 1L,
            startTime = 1_742_000_000_000,
            endTime = 1_742_000_000_000 + durationSeconds * 1000,
            durationSeconds = durationSeconds,
            runMode = runMode
        )

        fun aRunStartedAt(localDateTime: String, runMode: String = "outdoor") = RunnerSession(
            id = 1L,
            startTime = java.time.LocalDateTime.parse(localDateTime).atZone(ZONE).toInstant().toEpochMilli(),
            runMode = runMode
        )

        fun samples(vararg readings: Pair<Number, Int>): List<HrSample> =
            readings.mapIndexed { index, (elapsedSeconds, bpm) ->
                HrSample(
                    id = index + 1L,
                    sessionId = 1L,
                    elapsedSeconds = elapsedSeconds.toLong(),
                    rawBpm = bpm,
                    smoothedBpm = bpm,
                    connectionState = if (bpm > 0) "CONNECTED" else "DISCONNECTED"
                )
            }
    }
}
