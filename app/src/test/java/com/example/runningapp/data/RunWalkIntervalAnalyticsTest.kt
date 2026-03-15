package com.example.runningapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RunWalkIntervalAnalyticsTest {

    @Test
    fun `computeRunWalkIntervalAnalytics classifies interval completion bands and summary rates`() {
        val stats = listOf(
            stat(intervalIndex = 0, planned = 60, actual = 12, triggers = 1, triggerSecond = 12),
            stat(intervalIndex = 1, planned = 60, actual = 30, triggers = 1, triggerSecond = 30),
            stat(intervalIndex = 2, planned = 60, actual = 48, triggers = 1, triggerSecond = 48),
            stat(intervalIndex = 3, planned = 60, actual = 60, triggers = 0, triggerSecond = null)
        )

        val analytics = computeRunWalkIntervalAnalytics(stats)

        assertEquals(4, analytics.totalIntervals)
        assertEquals(25, analytics.cleanPercent)
        assertEquals(30, analytics.avgTimeToTriggerSeconds)
        assertEquals(60, analytics.longestCleanSeconds)
        assertEquals(63, analytics.completionRatioPercent)
        assertEquals(1, analytics.severeBreakdownCount)
        assertEquals(25, analytics.severeBreakdownPercent)
        assertEquals(1, analytics.poorToleranceCount)
        assertEquals(25, analytics.poorTolerancePercent)
        assertEquals(1, analytics.strainedCompletionCount)
        assertEquals(25, analytics.strainedCompletionPercent)
        assertEquals(1, analytics.strongCompletionCount)
        assertEquals(25, analytics.strongCompletionPercent)
    }

    @Test
    fun `classifyIntervalCompletionBand uses completion ratio bands`() {
        assertEquals(
            IntervalCompletionBand.SEVERE_BREAKDOWN,
            classifyIntervalCompletionBand(stat(intervalIndex = 0, planned = 60, actual = 17, triggers = 1, triggerSecond = 17))
        )
        assertEquals(
            IntervalCompletionBand.POOR_TOLERANCE,
            classifyIntervalCompletionBand(stat(intervalIndex = 0, planned = 60, actual = 18, triggers = 1, triggerSecond = 18))
        )
        assertEquals(
            IntervalCompletionBand.STRAINED_COMPLETION,
            classifyIntervalCompletionBand(stat(intervalIndex = 0, planned = 60, actual = 36, triggers = 1, triggerSecond = 36))
        )
        assertEquals(
            IntervalCompletionBand.STRONG_COMPLETION,
            classifyIntervalCompletionBand(stat(intervalIndex = 0, planned = 60, actual = 54, triggers = 1, triggerSecond = 54))
        )
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
