package com.example.runningapp.training

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far into each goal the runner is (#82), against scripted histories.
 *
 * One zone throughout and wall clocks rather than epochs, for the same reason as [WeeklyVolumeTest]:
 * which period a Run lands in is the whole question these totals answer.
 */
class GoalsTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")

    /** A Monday in the middle of a month, so week, month and year boundaries are all readable. */
    private val monday: LocalDate = LocalDate.of(2026, 3, 9)

    private fun runAt(
        date: LocalDate,
        hour: Int = 9,
        minute: Int = 0,
        km: Double = 0.0,
        seconds: Long = 0,
    ) = VolumeRun(
        startedAtMillis = LocalDateTime.of(date, LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli(),
        distanceKm = km,
        timeSeconds = seconds,
        effortScore = null,
    )

    private fun goal(period: GoalPeriod, metric: GoalMetric, target: Double) =
        Goal(id = 1, period = period, metric = metric, target = target)

    private fun progressOf(
        goals: List<Goal>,
        runs: List<VolumeRun>,
        on: LocalDate = monday.plusDays(3),
    ) = goalProgressOf(goals, runs, on = on, zone = zone)

    @Test
    fun `a runner with no goals has nothing to show`() {
        assertEquals(
            emptyList<GoalProgress>(),
            progressOf(emptyList(), listOf(runAt(monday, km = 10.0))),
        )
    }

    @Test
    fun `a goal with no runs sits at nothing`() {
        val progress = progressOf(listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 40.0)), emptyList())

        assertEquals(0.0, progress.single().done, 0.0001)
        assertEquals(0.0, progress.single().fraction, 0.0001)
        assertFalse(progress.single().met)
    }

    @Test
    fun `a weekly goal counts from Monday and not before it`() {
        val progress = progressOf(
            listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 40.0)),
            listOf(
                // The Sunday before: last week's run, however recently it was run.
                runAt(monday.minusDays(1), km = 12.0),
                runAt(monday, km = 10.0),
                runAt(monday.plusDays(2), km = 14.0),
            ),
        )

        assertEquals(monday, progress.single().periodStart)
        assertEquals(24.0, progress.single().done, 0.0001)
    }

    @Test
    fun `a monthly goal counts from the first of the month`() {
        val progress = progressOf(
            listOf(goal(GoalPeriod.MONTH, GoalMetric.DISTANCE, 100.0)),
            listOf(
                runAt(LocalDate.of(2026, 2, 28), km = 12.0),
                runAt(LocalDate.of(2026, 3, 1), km = 10.0),
                runAt(monday, km = 14.0),
            ),
        )

        assertEquals(LocalDate.of(2026, 3, 1), progress.single().periodStart)
        assertEquals(24.0, progress.single().done, 0.0001)
    }

    @Test
    fun `an annual goal counts from the first of January`() {
        val progress = progressOf(
            listOf(goal(GoalPeriod.YEAR, GoalMetric.DISTANCE, 1000.0)),
            listOf(
                runAt(LocalDate.of(2025, 12, 31), km = 12.0),
                runAt(LocalDate.of(2026, 1, 1), km = 10.0),
                runAt(monday, km = 14.0),
            ),
        )

        assertEquals(LocalDate.of(2026, 1, 1), progress.single().periodStart)
        assertEquals(24.0, progress.single().done, 0.0001)
    }

    @Test
    fun `a goal renews at the period boundary without being re-entered`() {
        val weekly = goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 20.0)
        val lastWeeksRun = listOf(runAt(monday.minusDays(2), km = 25.0))

        val duringLastWeek = goalProgressOf(listOf(weekly), lastWeeksRun, on = monday.minusDays(1), zone = zone)
        val duringThisWeek = goalProgressOf(listOf(weekly), lastWeeksRun, on = monday, zone = zone)

        assertTrue(duringLastWeek.single().met)
        // The same goal, unedited: the new week starts it over rather than carrying the old total in.
        assertEquals(monday, duringThisWeek.single().periodStart)
        assertEquals(0.0, duringThisWeek.single().done, 0.0001)
        assertFalse(duringThisWeek.single().met)
    }

    @Test
    fun `a run belongs to the period it set off in, whatever midnight did`() {
        // Away at 23:30 on the Sunday, home in the small hours of the Monday. The runner would say
        // they ran on the Sunday, so the Sunday's week is where it counts — the same start-date rule
        // the curves and the weekly bars keep (#60).
        val progress = progressOf(
            listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 40.0)),
            listOf(runAt(monday.minusDays(1), hour = 23, minute = 30, km = 15.0)),
        )

        assertEquals(0.0, progress.single().done, 0.0001)
    }

    @Test
    fun `a run stamped in the future is left out`() {
        val progress = progressOf(
            listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 40.0)),
            listOf(
                runAt(monday, km = 10.0),
                // A phone whose clock has moved — the same guard the curves and the bars keep.
                runAt(monday.plusDays(5), km = 30.0),
            ),
            on = monday.plusDays(3),
        )

        assertEquals(10.0, progress.single().done, 0.0001)
    }

    @Test
    fun `time is counted in hours and every session counts`() {
        val progress = progressOf(
            listOf(goal(GoalPeriod.WEEK, GoalMetric.TIME, 4.0)),
            listOf(
                runAt(monday, seconds = 3_600),
                // A treadmill Run: no distance recorded, but an hour of training all the same.
                runAt(monday.plusDays(1), km = 0.0, seconds = 1_800),
            ),
        )

        assertEquals(1.5, progress.single().done, 0.0001)
        assertEquals(1.5 / 4.0, progress.single().fraction, 0.0001)
    }

    @Test
    fun `a session with no distance counts towards time and count but not distance`() {
        val progress = progressOf(
            listOf(
                Goal(1, GoalPeriod.WEEK, GoalMetric.DISTANCE, 10.0),
                Goal(2, GoalPeriod.WEEK, GoalMetric.TIME, 2.0),
                Goal(3, GoalPeriod.WEEK, GoalMetric.COUNT, 3.0),
            ),
            listOf(
                runAt(monday, km = 5.0, seconds = 1_800),
                runAt(monday.plusDays(1), km = 0.0, seconds = 1_800),
            ),
        )

        assertEquals(5.0, progress[0].done, 0.0001)
        assertEquals(1.0, progress[1].done, 0.0001)
        assertEquals(2.0, progress[2].done, 0.0001)
    }

    @Test
    fun `a treadmill run the runner stated a distance for counts that distance`() {
        // A Stated Distance is the number off the console, and it counts everywhere a measured one
        // does (#231). What a treadmill Run never adds is a distance nobody stated — the run above.
        val progress = progressOf(
            listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 20.0)),
            listOf(runAt(monday, km = 6.4, seconds = 2_400)),
        )

        assertEquals(6.4, progress.single().done, 0.0001)
    }

    @Test
    fun `several goals are tracked at once, each on its own period`() {
        val progress = progressOf(
            listOf(
                Goal(1, GoalPeriod.WEEK, GoalMetric.DISTANCE, 40.0),
                Goal(2, GoalPeriod.YEAR, GoalMetric.COUNT, 200.0),
            ),
            listOf(
                runAt(LocalDate.of(2026, 1, 5), km = 8.0),
                runAt(monday, km = 10.0),
            ),
        )

        assertEquals(10.0, progress[0].done, 0.0001)
        assertEquals(2.0, progress[1].done, 0.0001)
    }

    @Test
    fun `a met goal is met, and stays met past its target`() {
        val progress = progressOf(
            listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 20.0)),
            listOf(runAt(monday, km = 25.0)),
        )

        assertTrue(progress.single().met)
        // The bar has nowhere further to go, so it stops at full rather than overflowing its own
        // width — but the total says 25, because that is what was run.
        assertEquals(1.0, progress.single().fraction, 0.0001)
        assertEquals(25.0, progress.single().done, 0.0001)
    }

    @Test
    fun `a goal edited mid-period is measured against the same runs`() {
        val runs = listOf(runAt(monday, km = 25.0))

        val before = progressOf(listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 20.0)), runs)
        val after = progressOf(listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 30.0)), runs)

        assertTrue(before.single().met)
        // Nothing is banked: raising the target mid-week re-reads the week the runner has had, so a
        // goal that was met is honestly no longer met.
        assertFalse(after.single().met)
        assertEquals(25.0, after.single().done, 0.0001)
    }

    @Test
    fun `a Run keeps the week it was run in when the phone has since flown`() {
        // #304: 23:30 on the Sunday before [monday] closes the previous week in London. Read in
        // Sydney it is Monday morning, which would credit this week's goal with last week's Run.
        val lastSunday = monday.minusDays(1)
        val lateOnSunday = LocalDateTime.of(lastSunday, LocalTime.of(23, 30))
            .atZone(zone).toInstant().toEpochMilli()
        val run = VolumeRun(lateOnSunday, 5.0, 1800, null, ranAtUtcOffsetSeconds = 0)

        val progress = goalProgressOf(
            listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 20.0)),
            listOf(run),
            on = monday.plusDays(3),
            zone = ZoneId.of("Australia/Sydney"),
        )

        assertEquals(0.0, progress.single().done, 0.0001)
    }

    @Test
    fun `a Run that wrote down no offset is still read in the phone's zone`() {
        val lastSunday = monday.minusDays(1)
        val lateOnSunday = LocalDateTime.of(lastSunday, LocalTime.of(23, 30))
            .atZone(zone).toInstant().toEpochMilli()
        val run = VolumeRun(lateOnSunday, 5.0, 1800, null, ranAtUtcOffsetSeconds = null)

        val progress = goalProgressOf(
            listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 20.0)),
            listOf(run),
            on = monday.plusDays(3),
            zone = ZoneId.of("Australia/Sydney"),
        )

        assertEquals(5.0, progress.single().done, 0.0001)
    }

    @Test
    fun `a Run a day ahead of the phone still counts towards this week's goal`() {
        // #304: the Run's own day leads the phone's by one because the runner has flown west.
        val on = monday.plusDays(3)
        val ranTomorrow = LocalDateTime.of(on.plusDays(1), LocalTime.of(9, 0))
            .atZone(zone).toInstant().toEpochMilli()
        val run = VolumeRun(ranTomorrow, 5.0, 1800, null, ranAtUtcOffsetSeconds = 0)

        val progress = goalProgressOf(
            listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 20.0)),
            listOf(run),
            on = on,
            zone = zone,
        )

        assertEquals(5.0, progress.single().done, 0.0001)
    }

    @Test
    fun `a Run a day ahead across the Monday belongs to the week it fell in, not this one`() {
        // #304: the phone is still on Sunday and the runner, having flown west, is on Monday. The
        // Run is real and a day ahead is allowed, but it is next week's Run — a week that has
        // already closed cannot be met by a Run from after it closed.
        val sunday = monday.plusDays(6)
        val nextMonday = LocalDateTime.of(sunday.plusDays(1), LocalTime.of(9, 0))
            .atZone(zone).toInstant().toEpochMilli()
        val run = VolumeRun(nextMonday, 5.0, 1800, null, ranAtUtcOffsetSeconds = 0)

        val progress = goalProgressOf(
            listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 20.0)),
            listOf(run),
            on = sunday,
            zone = zone,
        )

        assertEquals(0.0, progress.single().done, 0.0001)
    }

    @Test
    fun `a Run a day ahead across the first of the month belongs to the new month`() {
        // The same rule, stated once for every period: read off the Run's own period start.
        val lastDay = LocalDate.of(2026, 3, 31)
        val firstOfApril = LocalDateTime.of(lastDay.plusDays(1), LocalTime.of(9, 0))
            .atZone(zone).toInstant().toEpochMilli()
        val run = VolumeRun(firstOfApril, 5.0, 1800, null, ranAtUtcOffsetSeconds = 0)

        val progress = goalProgressOf(
            listOf(goal(GoalPeriod.MONTH, GoalMetric.DISTANCE, 20.0)),
            listOf(run),
            on = lastDay,
            zone = zone,
        )

        assertEquals(0.0, progress.single().done, 0.0001)
    }

    @Test
    fun `a Run stamped further ahead than any clock allows is still ignored`() {
        val on = monday.plusDays(3)
        val wayAhead = LocalDateTime.of(on.plusDays(2), LocalTime.of(9, 0))
            .atZone(zone).toInstant().toEpochMilli()
        val run = VolumeRun(wayAhead, 5.0, 1800, null, ranAtUtcOffsetSeconds = 0)

        val progress = goalProgressOf(
            listOf(goal(GoalPeriod.WEEK, GoalMetric.DISTANCE, 20.0)),
            listOf(run),
            on = on,
            zone = zone,
        )

        assertEquals(0.0, progress.single().done, 0.0001)
    }
}
