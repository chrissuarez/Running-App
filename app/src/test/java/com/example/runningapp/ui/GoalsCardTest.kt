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
        assertEquals(40.0, goalTargetOf(GoalMetric.DISTANCE, "40")!!, 0.0001)
        assertEquals(4.5, goalTargetOf(GoalMetric.DISTANCE, "4,5")!!, 0.0001)
        // Nothing to save rather than a goal of nothing: a target of zero is met before it is set.
        assertNull(goalTargetOf(GoalMetric.DISTANCE, "0"))
        assertNull(goalTargetOf(GoalMetric.DISTANCE, "-5"))
        assertNull(goalTargetOf(GoalMetric.DISTANCE, "forty"))
        assertNull(goalTargetOf(GoalMetric.DISTANCE, ""))
    }

    @Test
    fun `a part of a run is no target at all`() {
        // Runs arrive whole, so 2.1 of them is a week nobody can finish: refused where it is typed,
        // rather than rounded behind the runner into a goal they did not set.
        assertNull(goalTargetOf(GoalMetric.COUNT, "2.1"))
        assertNull(goalTargetOf(GoalMetric.COUNT, "2,5"))
        assertNull(goalTargetOf(GoalMetric.COUNT, "0.5"))
        assertEquals(3.0, goalTargetOf(GoalMetric.COUNT, "3")!!, 0.0001)
        assertEquals(3.0, goalTargetOf(GoalMetric.COUNT, "3.0")!!, 0.0001)
        // Hours and kilometres are still free to be part of one.
        assertEquals(4.5, goalTargetOf(GoalMetric.TIME, "4.5")!!, 0.0001)
        assertEquals(2.1, goalTargetOf(GoalMetric.DISTANCE, "2.1")!!, 0.0001)
    }

    @Test
    fun `the target field follows the pair that is chosen`() {
        val weeklyTime = Goal(id = 2, period = GoalPeriod.WEEK, metric = GoalMetric.TIME, target = 4.0)
        // Choosing a pair with a goal standing offers that goal's target to be changed, so a 40 typed
        // against the distance goal can never be saved as 40 hours.
        assertEquals("4", goalFieldOf(weeklyTime))
        assertEquals("23.7", goalFieldOf(weeklyTime.copy(metric = GoalMetric.DISTANCE, target = 23.68)))
        assertEquals("3", goalFieldOf(weeklyTime.copy(metric = GoalMetric.COUNT, target = 3.0)))
        // And a pair with no goal standing starts empty, because there is nothing to change.
        assertEquals("", goalFieldOf(null))
    }

    @Test
    fun `a refused runs target is explained in runs`() {
        assertEquals("Enter a whole number of runs, like 3", goalTargetHintOf(GoalMetric.COUNT))
        assertEquals(
            "Enter a number greater than zero, like 40",
            goalTargetHintOf(GoalMetric.DISTANCE),
        )
    }
}
