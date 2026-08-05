package com.example.runningapp.training

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curve math #63 pins Fitness, Fatigue and Form to, against scripted histories.
 *
 * Everything here is in one zone deliberately: which calendar day a Run belongs to is the whole
 * question the daily total answers, so the tests state a wall clock and a zone rather than epochs.
 */
class ProgressTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val day1: LocalDate = LocalDate.of(2026, 3, 1)

    private fun runAt(date: LocalDate, hour: Int, minute: Int, score: Int) = ScoredRun(
        startedAtMillis = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli(),
        effortScore = score,
    )

    private fun everyDay(days: Int, score: Int): List<ScoredRun> =
        (0 until days).map { runAt(day1.plusDays(it.toLong()), 9, 0, score) }

    @Test
    fun `a constant daily effort pulls Fitness up toward that effort`() {
        val curve = progressCurve(everyDay(365, 50), through = day1.plusDays(364), zone = zone)

        // Six weeks in it is well short of the load it is climbing toward; a year in it has all but
        // arrived. That is what a 42-day average is for — it is slow on purpose.
        val sixWeeks = curve.first { it.date == day1.plusDays(41) }
        assertTrue("six weeks: ${sixWeeks.fitness}", sixWeeks.fitness in 30.0..35.0)
        assertEquals(50.0, curve.last().fitness, 0.5)
    }

    @Test
    fun `Fatigue arrives at the same effort long before Fitness does`() {
        val curve = progressCurve(everyDay(60, 50), through = day1.plusDays(59), zone = zone)

        val sixWeeks = curve.first { it.date == day1.plusDays(41) }
        assertEquals(50.0, sixWeeks.fatigue, 0.5)
        assertTrue("Fitness should still be climbing", sixWeeks.fitness < sixWeeks.fatigue)
    }

    @Test
    fun `rest days decay both curves and neither is skipped`() {
        val curve = progressCurve(
            listOf(runAt(day1, 9, 0, 100)),
            through = day1.plusDays(3),
            zone = zone,
        )

        // A day the runner did not run is still a day: four days asked for, four days back.
        assertEquals(listOf(day1, day1.plusDays(1), day1.plusDays(2), day1.plusDays(3)), curve.map { it.date })

        val first = curve.first()
        curve.drop(1).forEachIndexed { index, day ->
            val restDays = index + 1
            assertEquals(
                "fitness after $restDays rest days",
                first.fitness * Math.exp(-restDays / 42.0),
                day.fitness,
                1e-9,
            )
            assertEquals(
                "fatigue after $restDays rest days",
                first.fatigue * Math.exp(-restDays / 7.0),
                day.fatigue,
                1e-9,
            )
        }
        // Fatigue sheds a far larger share of itself over the same rest — which is the whole reason
        // resting makes a runner fresher. In absolute terms it is still the bigger of the two here,
        // because one hard day put it far higher to begin with.
        assertTrue(
            "fatigue sheds more of itself than fitness does",
            curve.last().fatigue / first.fatigue < curve.last().fitness / first.fitness
        )
        // And so each rest day leaves the runner fresher than the last: Form climbs back toward 0
        // from the dip the hard day put it in.
        curve.drop(1).zipWithNext { earlier, later ->
            assertTrue("Form recovers with rest", later.form > earlier.form)
        }
        assertTrue("a hard day costs Form", curve.last().form < 0.0)
    }

    @Test
    fun `the first ever Run starts the curve on its own day`() {
        val curve = progressCurve(listOf(runAt(day1, 9, 0, 100)), through = day1, zone = zone)

        assertEquals(1, curve.size)
        // From nothing, one day of 100: each curve moves its own fraction of the way there.
        assertEquals(100 * (1 - Math.exp(-1 / 42.0)), curve.single().fitness, 1e-9)
        assertEquals(100 * (1 - Math.exp(-1 / 7.0)), curve.single().fatigue, 1e-9)
        // Form is read off yesterday, and before the first Run there was no yesterday.
        assertEquals(0.0, curve.single().form, 1e-9)
    }

    @Test
    fun `a Run that crosses midnight belongs to the day it started`() {
        val lateNight = runAt(day1, 23, 50, 80)

        assertEquals(mapOf(day1 to 80), dailyEffortOf(listOf(lateNight), zone))

        val curve = progressCurve(listOf(lateNight), through = day1.plusDays(1), zone = zone)
        assertEquals(day1, curve.first().date)
        assertTrue("the day it ended banked nothing of its own", curve.last().fitness < curve.first().fitness)
    }

    @Test
    fun `two Runs on one day are one day's effort`() {
        val effort = dailyEffortOf(
            listOf(runAt(day1, 7, 0, 40), runAt(day1, 18, 0, 60), runAt(day1.plusDays(1), 7, 0, 10)),
            zone,
        )

        assertEquals(mapOf(day1 to 100, day1.plusDays(1) to 10), effort)
    }

    @Test
    fun `Form is yesterday's Fitness less yesterday's Fatigue`() {
        val curve = progressCurve(everyDay(30, 50), through = day1.plusDays(29), zone = zone)

        curve.zipWithNext { yesterday, today ->
            assertEquals(yesterday.fitness - yesterday.fatigue, today.form, 1e-9)
        }
    }

    @Test
    fun `the verdict bands are open above ten and below minus ten`() {
        assertEquals(FormVerdict.FRESH, formVerdictOf(10.01))
        assertEquals(FormVerdict.NEUTRAL, formVerdictOf(10.0))
        assertEquals(FormVerdict.NEUTRAL, formVerdictOf(0.0))
        assertEquals(FormVerdict.NEUTRAL, formVerdictOf(-10.0))
        assertEquals(FormVerdict.FATIGUED, formVerdictOf(-10.01))
    }

    @Test
    fun `a history of no Runs has no curve at all`() {
        assertEquals(emptyList<ProgressDay>(), progressCurve(emptyList(), through = day1, zone = zone))
    }

    @Test
    fun `Runs after the day asked for are left out of the curve`() {
        // A phone whose clock is behind a Run's own stamp must not draw a day that has not happened.
        val curve = progressCurve(
            listOf(runAt(day1, 9, 0, 100), runAt(day1.plusDays(5), 9, 0, 100)),
            through = day1.plusDays(2),
            zone = zone,
        )

        assertEquals(day1.plusDays(2), curve.last().date)
    }

    @Test
    fun `a range keeps the tail of the curve without changing it`() {
        val curve = progressCurve(everyDay(400, 50), through = day1.plusDays(399), zone = zone)

        val threeMonths = curve.within(ProgressRange.THREE_MONTHS, endingOn = day1.plusDays(399))

        assertEquals(day1.plusDays(399).minusMonths(3), threeMonths.first().date)
        assertEquals(curve.last(), threeMonths.last())
        // Windowed, not recomputed: the visible curve still carries the warm-up of everything
        // before it, which is the whole reason a three-month view is not a three-month history.
        assertEquals(50.0, threeMonths.first().fitness, 0.5)
    }
}
