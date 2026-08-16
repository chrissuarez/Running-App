package com.example.runningapp.training

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weekly rollup math the volume bars are drawn from (#64), against scripted histories.
 *
 * One zone throughout and wall clocks rather than epochs, for the same reason as [ProgressTest]:
 * which week a Run lands in is the whole question these totals answer.
 */
class WeeklyVolumeTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")

    /** A Monday, so the week boundaries in these tests can be read off the dates. */
    private val monday: LocalDate = LocalDate.of(2026, 3, 2)

    private fun runAt(
        date: LocalDate,
        hour: Int = 9,
        minute: Int = 0,
        km: Double = 0.0,
        seconds: Long = 0,
        score: Int? = null,
    ) = VolumeRun(
        startedAtMillis = LocalDateTime.of(date, LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli(),
        distanceKm = km,
        timeSeconds = seconds,
        effortScore = score,
    )

    @Test
    fun `a history with no runs has no weeks`() {
        assertEquals(emptyList<TrainingWeek>(), weeklyVolumeOf(emptyList(), monday, zone))
    }

    @Test
    fun `every week starts on a Monday`() {
        // A first Run on the Thursday: the week it belongs to still starts on the Monday before it.
        val weeks = weeklyVolumeOf(
            listOf(runAt(monday.plusDays(3), km = 5.0)),
            through = monday.plusDays(20),
            zone = zone,
        )

        assertEquals(monday, weeks.first().startingOn)
        assertTrue(weeks.all { it.startingOn.dayOfWeek == java.time.DayOfWeek.MONDAY })
    }

    @Test
    fun `a Sunday night run belongs to the week that began six days earlier`() {
        val weeks = weeklyVolumeOf(
            listOf(
                runAt(monday, km = 5.0),
                runAt(monday.plusDays(6), hour = 23, minute = 30, km = 8.0),
            ),
            through = monday.plusDays(6),
            zone = zone,
        )

        assertEquals(1, weeks.size)
        assertEquals(13.0, weeks.single().distanceKm, 0.001)
    }

    @Test
    fun `the Monday after closes the week and opens the next`() {
        val weeks = weeklyVolumeOf(
            listOf(
                runAt(monday.plusDays(6), km = 8.0),
                runAt(monday.plusDays(7), km = 3.0),
            ),
            through = monday.plusDays(7),
            zone = zone,
        )

        assertEquals(listOf(monday, monday.plusDays(7)), weeks.map { it.startingOn })
        assertEquals(8.0, weeks[0].distanceKm, 0.001)
        assertEquals(3.0, weeks[1].distanceKm, 0.001)
    }

    @Test
    fun `a week's runs add up in all three measures`() {
        val weeks = weeklyVolumeOf(
            listOf(
                runAt(monday, km = 5.0, seconds = 1_800, score = 40),
                runAt(monday.plusDays(2), km = 10.5, seconds = 3_600, score = 90),
            ),
            through = monday.plusDays(6),
            zone = zone,
        )

        val week = weeks.single()
        assertEquals(15.5, week.distanceKm, 0.001)
        assertEquals(5_400L, week.timeSeconds)
        assertEquals(130, week.effortScore)
    }

    @Test
    fun `a week nobody ran in is a zero and never a gap`() {
        val weeks = weeklyVolumeOf(
            listOf(runAt(monday, km = 5.0), runAt(monday.plusDays(21), km = 6.0)),
            through = monday.plusDays(21),
            zone = zone,
        )

        // Four weeks between the two Runs inclusive, and the two in the middle are rest weeks that
        // have to be drawn as such — a bar chart that left them out would put the two Runs side by
        // side and call a fortnight off a steady fortnight.
        assertEquals(4, weeks.size)
        assertEquals(0.0, weeks[1].distanceKm, 0.001)
        assertEquals(0.0, weeks[2].distanceKm, 0.001)
        assertEquals(0L, weeks[1].timeSeconds)
        // Zero kilometres but no Score at all: nothing was measured in a week nobody ran in.
        assertNull(weeks[1].effortScore)
    }

    @Test
    fun `the week in progress is shown, counting only what has been run so far`() {
        // Wednesday of the second week: the week is half over and has one Run in it.
        val wednesday = monday.plusDays(9)
        val weeks = weeklyVolumeOf(
            listOf(runAt(monday, km = 30.0), runAt(monday.plusDays(8), km = 4.0)),
            through = wednesday,
            zone = zone,
        )

        assertEquals(listOf(monday, monday.plusDays(7)), weeks.map { it.startingOn })
        assertEquals(4.0, weeks.last().distanceKm, 0.001)
    }

    @Test
    fun `a Run stamped in the future cannot land in this week`() {
        val weeks = weeklyVolumeOf(
            listOf(runAt(monday, km = 5.0), runAt(monday.plusDays(30), km = 99.0)),
            through = monday.plusDays(6),
            zone = zone,
        )

        assertEquals(1, weeks.size)
        assertEquals(5.0, weeks.single().distanceKm, 0.001)
    }

    @Test
    fun `a Run stamped later this week cannot land in the bar for the week in progress`() {
        // Wednesday, with a Run dated the coming Sunday: it shares this week's Monday, so a guard
        // that only compared week starts would count tomorrow's training in today's bar.
        val wednesday = monday.plusDays(2)
        val weeks = weeklyVolumeOf(
            listOf(runAt(monday, km = 5.0), runAt(monday.plusDays(6), km = 99.0)),
            through = wednesday,
            zone = zone,
        )

        assertEquals(5.0, weeks.single().distanceKm, 0.001)
    }

    @Test
    fun `a week of Runs that all scored zero is a measured zero, not a missing Score`() {
        // A Strap was worn and the whole week stayed below Zone 1 — 0 is the answer, and it has to
        // read differently from a week that measured nothing at all.
        val weeks = weeklyVolumeOf(
            listOf(runAt(monday, km = 5.0, score = 0), runAt(monday.plusDays(1), km = 4.0, score = 0)),
            through = monday.plusDays(6),
            zone = zone,
        )

        assertEquals(0, weeks.single().effortScore)
    }

    @Test
    fun `a week whose Runs were all unmeasured has no Effort Score at all`() {
        val weeks = weeklyVolumeOf(
            listOf(runAt(monday, km = 5.0, score = null)),
            through = monday.plusDays(6),
            zone = zone,
        )

        assertNull(weeks.single().effortScore)
    }

    @Test
    fun `a Run with no Effort Score adds nothing to the week's Effort but still adds its distance`() {
        val weeks = weeklyVolumeOf(
            listOf(
                runAt(monday, km = 5.0, seconds = 1_800, score = 40),
                runAt(monday.plusDays(1), km = 7.0, seconds = 2_400, score = null),
            ),
            through = monday.plusDays(6),
            zone = zone,
        )

        val week = weeks.single()
        assertEquals(40, week.effortScore)
        assertEquals(12.0, week.distanceKm, 0.001)
        assertEquals(4_200L, week.timeSeconds)
    }

    @Test
    fun `the clocks going forward does not move a Run into the next week`() {
        // Sunday 29 March 2026 is the morning the UK clocks go forward, and 01:30 that day is the
        // hour that does not exist locally — the epoch it resolves to is 02:30 BST. It is still the
        // Sunday, so it still belongs to the week that started on the Monday before it, and a week
        // boundary worked out on a fixed number of hours rather than on the calendar would put it
        // in the next one.
        val dstSunday = LocalDate.of(2026, 3, 29)
        val weeks = weeklyVolumeOf(
            listOf(runAt(dstSunday, hour = 1, minute = 30, km = 9.0)),
            through = dstSunday,
            zone = zone,
        )

        assertEquals(LocalDate.of(2026, 3, 23), weeks.single().startingOn)
        assertEquals(9.0, weeks.single().distanceKm, 0.001)
    }

    @Test
    fun `a range keeps only the weeks that begin inside it`() {
        val weeks = weeklyVolumeOf(
            listOf(runAt(monday, km = 5.0)),
            through = monday.plusWeeks(52),
            zone = zone,
        )
        val endingOn = monday.plusWeeks(52)

        val threeMonths = weeks.within(ProgressRange.THREE_MONTHS, endingOn = endingOn)

        val from = ProgressRange.THREE_MONTHS.startOn(endingOn)
        assertTrue(threeMonths.first().startingOn >= from)
        // The week that straddles the range's first day is dropped rather than drawn short: a bar
        // holding four days of a seven-day week reads as an easy week nobody had.
        assertTrue(threeMonths.first().startingOn.minusWeeks(1) < from)
        assertEquals(weeks.last(), threeMonths.last())
    }

    @Test
    fun `a longer range shows the same weeks with more in front of them`() {
        val weeks = weeklyVolumeOf(
            listOf(runAt(monday, km = 5.0)),
            through = monday.plusWeeks(52),
            zone = zone,
        )
        val endingOn = monday.plusWeeks(52)

        val threeMonths = weeks.within(ProgressRange.THREE_MONTHS, endingOn = endingOn)
        val year = weeks.within(ProgressRange.ONE_YEAR, endingOn = endingOn)

        assertTrue(year.size > threeMonths.size)
        assertEquals(threeMonths, year.takeLast(threeMonths.size))
    }

    @Test
    fun `a week holding both kinds of Run says it was only partly measured`() {
        // The whole of #247: the total is the scored Run's alone, and nothing about the number says
        // the strapless hour beside it is missing from it.
        val weeks = weeklyVolumeOf(
            listOf(
                runAt(monday, km = 5.0, seconds = 1_800, score = 40),
                runAt(monday.plusDays(1), km = 12.0, seconds = 4_200, score = null),
            ),
            through = monday.plusDays(6),
            zone = zone,
        )

        val week = weeks.single()
        assertEquals(40, week.effortScore)
        assertEquals(1, week.runsWithoutScore)
        assertTrue(week.partlyMeasured)
    }

    @Test
    fun `a week every Run of which was measured is not partly measured`() {
        val weeks = weeklyVolumeOf(
            listOf(runAt(monday, score = 40), runAt(monday.plusDays(2), score = 0)),
            through = monday.plusDays(6),
            zone = zone,
        )

        assertEquals(0, weeks.single().runsWithoutScore)
        assertFalse(weeks.single().partlyMeasured)
    }

    @Test
    fun `a week nothing measured is not a partly measured week`() {
        // Nothing was measured, so there is no total for the unmeasured Runs to be short of. It has
        // its own way of saying so: no Effort Score at all.
        val weeks = weeklyVolumeOf(
            listOf(runAt(monday, score = null), runAt(monday.plusDays(2), score = null)),
            through = monday.plusDays(6),
            zone = zone,
        )

        assertNull(weeks.single().effortScore)
        assertEquals(2, weeks.single().runsWithoutScore)
        assertFalse(weeks.single().partlyMeasured)
    }

    @Test
    fun `a week nobody ran in holds no unmeasured Runs`() {
        // How a week of rest is told from a week of strapless Runs: both have no Effort Score, and
        // only one of them has Runs in it.
        val weeks = weeklyVolumeOf(
            listOf(runAt(monday, score = 40), runAt(monday.plusWeeks(2), score = 40)),
            through = monday.plusWeeks(2).plusDays(6),
            zone = zone,
        )

        assertEquals(0, weeks[1].runsWithoutScore)
        assertNull(weeks[1].effortScore)
    }

    @Test
    fun `a week whose bar is short of what was run is named under the chart`() {
        val measured = TrainingWeek(monday, distanceKm = 10.0, timeSeconds = 3_600, effortScore = 100, runsWithoutScore = 0)
        val partly = measured.copy(startingOn = monday.plusWeeks(1), runsWithoutScore = 1)

        assertNull(unmeasuredRunsNote(listOf(measured, measured), ::dateText))
        assertEquals(
            "The week of 9 Mar held a run with no heart rate recorded, so its bar is short of " +
                "what was run.",
            unmeasuredRunsNote(listOf(measured, partly), ::dateText),
        )
    }

    @Test
    fun `a week nothing measured is named too, its bar being the shortest of all`() {
        // Not a partly measured week — it has no Effort Score at all — and drawn at nothing beside
        // weeks that scored, it understates the runner further still.
        val measured = TrainingWeek(monday, distanceKm = 10.0, timeSeconds = 3_600, effortScore = 100, runsWithoutScore = 0)
        val strapless = measured.copy(
            startingOn = monday.plusWeeks(1),
            effortScore = null,
            runsWithoutScore = 2,
        )

        assertEquals(
            "The week of 9 Mar held runs with no heart rate recorded, so its bar is short of what " +
                "was run.",
            unmeasuredRunsNote(listOf(measured, strapless), ::dateText),
        )
    }

    @Test
    fun `two or three short weeks are named, and more than that are counted`() {
        val partly = TrainingWeek(monday, distanceKm = 10.0, timeSeconds = 3_600, effortScore = 100, runsWithoutScore = 2)
        val weeks = (0L..3L).map { partly.copy(startingOn = monday.plusWeeks(it)) }

        assertEquals(
            "The weeks of 2 Mar, 9 Mar and 16 Mar held runs with no heart rate recorded, so their " +
                "bars are short of what was run.",
            unmeasuredRunsNote(weeks.dropLast(1), ::dateText),
        )
        assertEquals(
            "4 of these weeks held runs with no heart rate recorded, so their bars are short of " +
                "what was run.",
            unmeasuredRunsNote(weeks, ::dateText),
        )
    }

    private fun dateText(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM"))

    @Test
    fun `each measure reads a week in its own unit`() {
        val week = TrainingWeek(
            startingOn = monday,
            distanceKm = 42.2,
            timeSeconds = 5_400,
            effortScore = 300,
            runsWithoutScore = 0,
        )

        assertEquals(42.2, WeeklyMeasure.DISTANCE.amountOf(week), 0.001)
        // Hours, not seconds: a bar axis labelled 5400 is not a number anybody trains in.
        assertEquals(1.5, WeeklyMeasure.TIME.amountOf(week), 0.001)
        assertEquals(300.0, WeeklyMeasure.EFFORT_SCORE.amountOf(week), 0.001)
        // A bar cannot draw "unknown", so an unmeasured week is flat like a measured zero. The two
        // are told apart in words on the screen, not in height.
        assertEquals(0.0, WeeklyMeasure.EFFORT_SCORE.amountOf(week.copy(effortScore = null)), 0.001)
    }

    @Test
    fun `a Run keeps the week it was run in when the phone has since flown`() {
        // #304: 23:30 on the Sunday in London closes that week. Read in Sydney it is Monday
        // morning and starts the next one, which is a week the runner never ran.
        val sunday = monday.plusDays(6)
        val lateOnSunday = LocalDateTime.of(sunday, LocalTime.of(23, 30))
            .atZone(zone).toInstant().toEpochMilli()
        val run = VolumeRun(
            startedAtMillis = lateOnSunday,
            distanceKm = 5.0,
            timeSeconds = 1800,
            effortScore = null,
            ranAtUtcOffsetSeconds = 0,
        )
        val sydney = ZoneId.of("Australia/Sydney")

        val weeks = weeklyVolumeOf(listOf(run), through = sunday.plusDays(1), zone = sydney)

        assertEquals(monday, weeks.first { it.distanceKm > 0.0 }.startingOn)
    }

    @Test
    fun `a Run that wrote down no offset is still read in the phone's zone`() {
        // Every Run recorded before v32. Nothing about their behaviour changes.
        val sunday = monday.plusDays(6)
        val lateOnSunday = LocalDateTime.of(sunday, LocalTime.of(23, 30))
            .atZone(zone).toInstant().toEpochMilli()
        val run = VolumeRun(lateOnSunday, 5.0, 1800, null, ranAtUtcOffsetSeconds = null)

        val weeks = weeklyVolumeOf(listOf(run), through = sunday.plusDays(2), zone = ZoneId.of("Australia/Sydney"))

        assertEquals(monday.plusDays(7), weeks.first { it.distanceKm > 0.0 }.startingOn)
    }

    @Test
    fun `a Run a day ahead of the phone still counts towards its own week`() {
        // #304: ran Saturday evening in Sydney, landed in London on Saturday morning. The Run's own
        // day is the phone's tomorrow, and it is a Run the runner really ran this week.
        val saturday = monday.plusDays(5)
        val saturdayEveningInSydney = LocalDateTime.of(saturday, LocalTime.of(19, 0))
            .atZone(ZoneId.of("Australia/Sydney")).toInstant().toEpochMilli()
        val run = VolumeRun(saturdayEveningInSydney, 10.0, 3600, null, ranAtUtcOffsetSeconds = 10 * 3600)

        val weeks = weeklyVolumeOf(listOf(run), through = monday.plusDays(4), zone = zone)

        assertEquals(10.0, weeks.single { it.startingOn == monday }.distanceKm, 0.0001)
    }

    @Test
    fun `a Run stamped further ahead than any clock allows is still ignored`() {
        val wayAhead = LocalDateTime.of(monday.plusDays(30), LocalTime.of(9, 0))
            .atZone(zone).toInstant().toEpochMilli()
        val run = VolumeRun(wayAhead, 10.0, 3600, null, ranAtUtcOffsetSeconds = 0)

        assertEquals(
            emptyList<TrainingWeek>(),
            weeklyVolumeOf(listOf(run), through = monday.plusDays(4), zone = zone),
        )
    }
}
