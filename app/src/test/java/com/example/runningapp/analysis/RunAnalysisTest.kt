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
            samples = samples(0 to 120, 60 to 130, 120 to 140, 180 to 125)
        )

        val chart = requireNotNull(analysis.chart)
        assertEquals(
            listOf(
                HeartRateReading(0L, 120),
                HeartRateReading(60L, 130),
                HeartRateReading(120L, 140),
                HeartRateReading(180L, 125)
            ),
            chart.heartRate.single().readings
        )
        assertEquals(180L, chart.elapsedSecondsSpan)
    }

    @Test
    fun `readings are charted in the run's own order, whatever order they arrive in`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 120),
            samples = samples(120 to 140, 0 to 120, 60 to 130)
        )

        assertEquals(
            listOf(0L, 60L, 120L),
            requireNotNull(analysis.chart).heartRate.single().readings.map { it.elapsedSeconds }
        )
    }

    @Test
    fun `a treadmill run charts the same as an outdoor one`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 120, runMode = "treadmill"),
            samples = samples(0 to 110, 60 to 120, 120 to 130)
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
    fun `the line breaks where the strap dropped out rather than diving to zero`() {
        // A minute of banked seconds with no beat in them: the strap was on the runner's chest and
        // reporting nothing, which is not a heart rate of zero.
        val lost = (10L..70L step 1).map { it to 0 }
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 90),
            samples = samples(0 to 120, 5 to 122, *lost.toTypedArray(), 75 to 130, 90 to 132)
        )

        val chart = requireNotNull(analysis.chart)
        assertEquals(2, chart.heartRate.size)
        assertEquals(listOf(HeartRateReading(0L, 120), HeartRateReading(5L, 122)), chart.heartRate[0].readings)
        assertEquals(listOf(HeartRateReading(75L, 130), HeartRateReading(90L, 132)), chart.heartRate[1].readings)
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
    fun `a run that says it banked every second breaks on a single missing one`() {
        // Rows that carry the wall clock of the second they were banked in come from a run that
        // wrote one row per second, so there is nothing to infer from their spacing: second 2 has
        // no row because the strap was lost in it.
        val chart = requireNotNull(
            RunAnalysis.of(
                run = aRun(durationSeconds = 4),
                samples = secondBySecondSamples(0 to 120, 1 to 121, 3 to 130, 4 to 131)
            ).chart
        )

        assertEquals(2, chart.heartRate.size)
        assertNull(chart.readingAt(2L))
    }

    @Test
    fun `a dropout cannot pass itself off as the cadence of a short recording`() {
        // Three readings, one dropout: the steps are one second and four, and if the recording is
        // read at its middle the dropout is half of it and elects itself as normal. Old enough not
        // to say how it was banked, this run must still be read at its tightest spacing.
        val chart = requireNotNull(
            RunAnalysis.of(
                run = aRun(durationSeconds = 5),
                samples = samples(0 to 120, 1 to 121, 5 to 130)
            ).chart
        )

        assertEquals(2, chart.heartRate.size)
        assertNull(chart.readingAt(3L))
    }

    @Test
    fun `a run recorded before every second was banked keeps one line through its sparse patches`() {
        // Old history was sampled irregularly; a few seconds between readings is sparseness, not a
        // dropped strap, and must not be drawn as a broken line.
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 30),
            samples = samples(0 to 120, 10 to 124, 20 to 128, 30 to 130)
        )

        assertEquals(1, requireNotNull(analysis.chart).heartRate.size)
    }

    @Test
    fun `a long silence in the recording breaks the line`() {
        val analysis = RunAnalysis.of(
            run = aRun(durationSeconds = 400),
            samples = samples(0 to 120, 30 to 124, 330 to 128, 360 to 130)
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
                run = aRun(durationSeconds = 120),
                samples = samples(0 to 120, 60 to 130, 120 to 140)
            ).chart
        )

        assertEquals(HeartRateReading(60L, 130), chart.readingAt(58L))
        assertEquals(HeartRateReading(120L, 140), chart.readingAt(119L))
        assertEquals(HeartRateReading(0L, 120), chart.readingAt(-5L))
    }

    @Test
    fun `dragging into a stretch the strap missed reads out nothing`() {
        val lost = (10L..70L step 1).map { it to 0 }
        val chart = requireNotNull(
            RunAnalysis.of(
                run = aRun(durationSeconds = 90),
                samples = samples(0 to 120, 5 to 122, *lost.toTypedArray(), 75 to 130, 90 to 132)
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

        /** Readings as history recorded them before a row knew its own wall clock. */
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

        /** Readings as a run records them today: a row a second, each stamped with its own moment. */
        fun secondBySecondSamples(vararg readings: Pair<Number, Int>): List<HrSample> =
            samples(*readings).map {
                it.copy(timestampMillis = 1_742_000_000_000 + it.elapsedSeconds * 1000)
            }
    }
}
