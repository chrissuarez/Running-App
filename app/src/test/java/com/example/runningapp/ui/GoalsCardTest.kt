package com.example.runningapp.ui

import com.example.runningapp.training.Goal
import com.example.runningapp.training.GoalMetric
import com.example.runningapp.training.GoalPeriod
import com.example.runningapp.training.GoalProgress
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a goal reads as on the card, and what the target field accepts (#82). */
class GoalsCardTest {

    private fun progress(metric: GoalMetric, done: Double, target: Double) = GoalProgress(
        goal = Goal(id = 1, period = GoalPeriod.WEEK, metric = metric, target = target),
        periodStart = LocalDate.of(2026, 8, 3),
        done = done,
    )

    @Test
    fun `a whole number is written whole`() {
        assertEquals("24", goalAmountText(GoalMetric.DISTANCE, 24.0))
        assertEquals("40", goalAmountText(GoalMetric.DISTANCE, 40.0))
    }

    @Test
    fun `a part kilometre is written to a tenth`() {
        assertEquals("23.7", goalAmountText(GoalMetric.DISTANCE, 23.68))
        assertEquals("1.5", goalAmountText(GoalMetric.TIME, 1.5))
    }

    @Test
    fun `runs are counted whole, never to a tenth`() {
        assertEquals("3", goalAmountText(GoalMetric.COUNT, 3.0))
    }

    @Test
    fun `a goal reads as the runner would say it`() {
        assertEquals(
            "This week — 24 / 40 km",
            goalLineOf(progress(GoalMetric.DISTANCE, done = 24.0, target = 40.0)),
        )
        assertEquals(
            "This week — 1.5 / 4 hours",
            goalLineOf(progress(GoalMetric.TIME, done = 1.5, target = 4.0)),
        )
    }

    @Test
    fun `a target is a positive number, and a comma is a decimal point`() {
        assertEquals(40.0, goalTargetOf("40")!!, 0.0001)
        assertEquals(4.5, goalTargetOf("4,5")!!, 0.0001)
        // Nothing to save rather than a goal of nothing: a target of zero is met before it is set.
        assertNull(goalTargetOf("0"))
        assertNull(goalTargetOf("-5"))
        assertNull(goalTargetOf("forty"))
        assertNull(goalTargetOf(""))
    }
}
