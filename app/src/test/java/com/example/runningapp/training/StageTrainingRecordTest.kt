package com.example.runningapp.training

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Stage's training record (#289) — the count that answers a requirement written in weeks, which
 * the three-Run window the coach is shown never could.
 */
class StageTrainingRecordTest {

    private fun day(iso: String) = LocalDate.parse(iso)

    @Test
    fun `a stage with no qualifying run has no record at all`() {
        val record = stageTrainingRecordOf(days = emptyList(), through = day("2026-08-30"))

        assertTrue(record.isEmpty)
        assertNull(record.firstRunOn)
        assertEquals(0, record.weeksTrained)
        assertEquals(0, record.qualifyingRuns)
    }

    @Test
    fun `runs are counted into the Monday-starting week they fell in`() {
        // Mon 2026-08-17 .. Sun 2026-08-23 holds the Saturday; the Sunday after starts a new week.
        val record = stageTrainingRecordOf(
            days = listOf(day("2026-08-17"), day("2026-08-22"), day("2026-08-23"), day("2026-08-24")),
            through = day("2026-08-24"),
        )

        assertEquals(
            listOf(
                StageWeek(day("2026-08-17"), 3),
                StageWeek(day("2026-08-24"), 1),
            ),
            record.weeks
        )
        assertEquals(4, record.qualifyingRuns)
        assertEquals(2, record.weeksTrained)
        assertEquals(day("2026-08-17"), record.firstRunOn)
    }

    @Test
    fun `a week nobody ran in is kept as a zero, because a gap is what consistent asks about`() {
        val record = stageTrainingRecordOf(
            days = listOf(day("2026-08-03"), day("2026-08-19")),
            through = day("2026-08-19"),
        )

        assertEquals(
            listOf(
                StageWeek(day("2026-08-03"), 1),
                StageWeek(day("2026-08-10"), 0),
                StageWeek(day("2026-08-17"), 1),
            ),
            record.weeks
        )
        assertEquals(3, record.weeksTrained)
    }

    @Test
    fun `the record runs through the week the runner is in, not the week they last ran`() {
        // Training stopped three weeks ago. Those three weeks are the whole point: a Stage that has
        // gone quiet is not a shorter Stage, it is one with empty weeks on the end.
        val record = stageTrainingRecordOf(
            days = listOf(day("2026-08-03")),
            through = day("2026-08-27"),
        )

        assertEquals(
            listOf(
                StageWeek(day("2026-08-03"), 1),
                StageWeek(day("2026-08-10"), 0),
                StageWeek(day("2026-08-17"), 0),
                StageWeek(day("2026-08-24"), 0),
            ),
            record.weeks
        )
        assertEquals(1, record.qualifyingRuns)
        assertEquals(4, record.weeksTrained)
    }

    @Test
    fun `a run dated further ahead than any clock could put it is not counted`() {
        val record = stageTrainingRecordOf(
            days = listOf(day("2026-08-24"), day("2026-11-30")),
            through = day("2026-08-30"),
        )

        assertEquals(1, record.qualifyingRuns)
        assertEquals(listOf(StageWeek(day("2026-08-24"), 1)), record.weeks)
    }

    @Test
    fun `a run one day ahead of the phone is kept, and gets its own week when it crosses a Monday`() {
        // The runner flew east: their Run is honestly a day ahead of the phone (#304).
        val record = stageTrainingRecordOf(
            days = listOf(day("2026-08-29"), day("2026-08-31")),
            through = day("2026-08-30"),
        )

        assertEquals(2, record.qualifyingRuns)
        assertEquals(
            listOf(
                StageWeek(day("2026-08-24"), 1),
                StageWeek(day("2026-08-31"), 1),
            ),
            record.weeks
        )
    }

    @Test
    fun `a long stage is listed twelve weeks deep, and still says how long and how many it really is`() {
        // Twenty weeks, one Run in each. The list is bounded; the two totals are not, so the coach
        // is never told a twenty-week stage is a twelve-week one.
        val start = day("2026-01-05") // a Monday
        val days = (0 until 20).map { start.plusWeeks(it.toLong()) }

        val record = stageTrainingRecordOf(days = days, through = start.plusWeeks(19))

        assertEquals(20, record.qualifyingRuns)
        assertEquals(20, record.weeksTrained)
        assertEquals(12, record.weeks.size)
        assertEquals(start.plusWeeks(8), record.weeks.first().startingOn)
        assertEquals(start.plusWeeks(19), record.weeks.last().startingOn)
    }
}
