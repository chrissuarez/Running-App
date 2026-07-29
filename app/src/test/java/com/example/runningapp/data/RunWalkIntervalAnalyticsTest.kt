package com.example.runningapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RunWalkIntervalAnalyticsTest {

    @Test
    fun `computeRunWalkIntervalAnalytics counts Intervals and times the first Trigger`() {
        val stats = listOf(
            stat(intervalIndex = 0, planned = 60, actual = 12, triggers = 1, triggerSecond = 12),
            stat(intervalIndex = 1, planned = 60, actual = 30, triggers = 1, triggerSecond = 30),
            stat(intervalIndex = 2, planned = 60, actual = 48, triggers = 1, triggerSecond = 48),
            stat(intervalIndex = 3, planned = 60, actual = 60, triggers = 0, triggerSecond = null)
        )

        val analytics = computeRunWalkIntervalAnalytics(stats)

        assertEquals(4, analytics.totalIntervals)
        assertEquals(1, analytics.intervalsWithNoTrigger)
        assertEquals(30, analytics.avgSecondsBeforeTrigger)
        assertEquals(60, analytics.longestIntervalWithNoTriggerSeconds)
    }

    @Test
    fun `an Interval run in full is not counted against a Run that never Triggered`() {
        val stats = listOf(
            stat(intervalIndex = 0, planned = 300, actual = 300, triggers = 0, triggerSecond = null),
            stat(intervalIndex = 1, planned = 300, actual = 120, triggers = 0, triggerSecond = null)
        )

        val analytics = computeRunWalkIntervalAnalytics(stats)

        assertEquals(2, analytics.intervalsWithNoTrigger)
        assertEquals(null, analytics.avgSecondsBeforeTrigger)
        assertEquals(300, analytics.longestIntervalWithNoTriggerSeconds)
    }

    @Test
    fun `a Run with no Intervals reports nothing rather than zero seconds`() {
        val analytics = computeRunWalkIntervalAnalytics(emptyList())

        assertEquals(0, analytics.totalIntervals)
        assertEquals(0, analytics.intervalsWithNoTrigger)
        assertEquals(null, analytics.avgSecondsBeforeTrigger)
        assertEquals(null, analytics.longestIntervalWithNoTriggerSeconds)
    }

    private fun stat(
        intervalIndex: Int,
        planned: Int,
        actual: Int,
        triggers: Int,
        triggerSecond: Int?
    ) = RunWalkIntervalStat(
        sessionId = 1L,
        intervalIndex = intervalIndex,
        plannedDurationSeconds = planned,
        actualRunningDurationBeforeHrTriggerSeconds = actual,
        timeIntoIntervalWhenHrExceededCapSeconds = triggerSecond,
        hrTriggerEvents = triggers,
        totalTimeSpentWalkingDuringRunIntervalSeconds = 0
    )
}
