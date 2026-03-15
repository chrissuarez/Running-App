package com.example.runningapp.data

import kotlin.math.roundToInt

enum class IntervalCompletionBand {
    SEVERE_BREAKDOWN,
    POOR_TOLERANCE,
    STRAINED_COMPLETION,
    STRONG_COMPLETION
}

data class RunWalkIntervalAnalytics(
    val totalIntervals: Int,
    val cleanPercent: Int,
    val avgTimeToTriggerSeconds: Int?,
    val longestCleanSeconds: Int?,
    val completionRatioPercent: Int,
    val severeBreakdownCount: Int,
    val severeBreakdownPercent: Int,
    val poorToleranceCount: Int,
    val poorTolerancePercent: Int,
    val strainedCompletionCount: Int,
    val strainedCompletionPercent: Int,
    val strongCompletionCount: Int,
    val strongCompletionPercent: Int
)

fun classifyIntervalCompletionBand(stat: RunWalkIntervalStat): IntervalCompletionBand {
    val completionRatio = if (stat.plannedDurationSeconds <= 0) {
        0.0
    } else {
        (stat.actualRunningDurationBeforeHrTriggerSeconds.toDouble() / stat.plannedDurationSeconds.toDouble())
            .coerceIn(0.0, 1.0)
    }

    return when {
        completionRatio < 0.30 -> IntervalCompletionBand.SEVERE_BREAKDOWN
        completionRatio < 0.60 -> IntervalCompletionBand.POOR_TOLERANCE
        completionRatio < 0.90 -> IntervalCompletionBand.STRAINED_COMPLETION
        else -> IntervalCompletionBand.STRONG_COMPLETION
    }
}

fun computeRunWalkIntervalAnalytics(intervalStats: List<RunWalkIntervalStat>): RunWalkIntervalAnalytics {
    val totalIntervals = intervalStats.size
    if (totalIntervals == 0) {
        return RunWalkIntervalAnalytics(
            totalIntervals = 0,
            cleanPercent = 0,
            avgTimeToTriggerSeconds = null,
            longestCleanSeconds = null,
            completionRatioPercent = 0,
            severeBreakdownCount = 0,
            severeBreakdownPercent = 0,
            poorToleranceCount = 0,
            poorTolerancePercent = 0,
            strainedCompletionCount = 0,
            strainedCompletionPercent = 0,
            strongCompletionCount = 0,
            strongCompletionPercent = 0
        )
    }

    val cleanCount = intervalStats.count { it.hrTriggerEvents == 0 }
    val triggeredTimes = intervalStats.mapNotNull { it.timeIntoIntervalWhenHrExceededCapSeconds }
    val longestCleanSeconds = intervalStats
        .asSequence()
        .filter { it.hrTriggerEvents == 0 }
        .map { it.actualRunningDurationBeforeHrTriggerSeconds }
        .maxOrNull()
    val completionRatioPercent = (
        intervalStats.map { stat ->
            if (stat.plannedDurationSeconds <= 0) {
                0.0
            } else {
                (stat.actualRunningDurationBeforeHrTriggerSeconds.toDouble() / stat.plannedDurationSeconds.toDouble())
                    .coerceAtMost(1.0)
            }
        }.average() * 100.0
        ).roundToInt()

    val bandCounts = intervalStats
        .groupingBy(::classifyIntervalCompletionBand)
        .eachCount()

    val severeBreakdownCount = bandCounts[IntervalCompletionBand.SEVERE_BREAKDOWN] ?: 0
    val poorToleranceCount = bandCounts[IntervalCompletionBand.POOR_TOLERANCE] ?: 0
    val strainedCompletionCount = bandCounts[IntervalCompletionBand.STRAINED_COMPLETION] ?: 0
    val strongCompletionCount = bandCounts[IntervalCompletionBand.STRONG_COMPLETION] ?: 0

    return RunWalkIntervalAnalytics(
        totalIntervals = totalIntervals,
        cleanPercent = percentRounded(cleanCount, totalIntervals),
        avgTimeToTriggerSeconds = triggeredTimes.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        longestCleanSeconds = longestCleanSeconds,
        completionRatioPercent = completionRatioPercent,
        severeBreakdownCount = severeBreakdownCount,
        severeBreakdownPercent = percentRounded(severeBreakdownCount, totalIntervals),
        poorToleranceCount = poorToleranceCount,
        poorTolerancePercent = percentRounded(poorToleranceCount, totalIntervals),
        strainedCompletionCount = strainedCompletionCount,
        strainedCompletionPercent = percentRounded(strainedCompletionCount, totalIntervals),
        strongCompletionCount = strongCompletionCount,
        strongCompletionPercent = percentRounded(strongCompletionCount, totalIntervals)
    )
}

fun percentRounded(part: Int, total: Int): Int {
    if (total <= 0) return 0
    return ((part.toDouble() / total.toDouble()) * 100.0).roundToInt()
}
