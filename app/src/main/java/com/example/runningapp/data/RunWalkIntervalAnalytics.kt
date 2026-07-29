package com.example.runningapp.data

import kotlin.math.roundToInt

/**
 * What the Run detail screen can honestly say about a Run's Intervals (#169).
 *
 * Every figure here is counted or timed from Triggers, because a Trigger is the only thing the app
 * records about how an Interval went. The completion ratio and its four bands used to live here;
 * all five were the same number — the second heart rate first crossed the target line, over the
 * Interval's planned length — and none of them measured completion, so an Interval run in full
 * logged as a "severe breakdown" (ADR 0003). They are deleted rather than renamed.
 */
data class RunWalkIntervalAnalytics(
    val totalIntervals: Int,
    val intervalsWithNoTrigger: Int,
    val avgSecondsBeforeTrigger: Int?,
    val longestIntervalWithNoTriggerSeconds: Int?
)

fun computeRunWalkIntervalAnalytics(intervalStats: List<RunWalkIntervalStat>): RunWalkIntervalAnalytics {
    val untriggered = intervalStats.filter { it.hrTriggerEvents == 0 }
    val secondsBeforeTrigger = intervalStats.mapNotNull { it.timeIntoIntervalWhenHrExceededCapSeconds }

    return RunWalkIntervalAnalytics(
        totalIntervals = intervalStats.size,
        intervalsWithNoTrigger = untriggered.size,
        avgSecondsBeforeTrigger = secondsBeforeTrigger.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        longestIntervalWithNoTriggerSeconds = untriggered
            .maxOfOrNull { it.actualRunningDurationBeforeHrTriggerSeconds }
    )
}
