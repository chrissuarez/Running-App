package com.example.runningapp.ui

/**
 * How a chart drawn against the calendar steps and labels its bottom axis.
 *
 * Written once because two charts already ask it — the times at a Segment (#72) and the paces on a
 * matched route (#73) — and both are the same picture of the same kind of history. Vico's own rule
 * is what makes this arithmetic rather than a preference, so a second copy of it would be a second
 * chance to get Vico wrong.
 */

/**
 * The whole number of days such a chart's x axis steps in.
 *
 * Vico steps an axis by the greatest common divisor of the gaps between its x values rather than by
 * one, so label spacing has to be counted in those steps and not in points. Six points a fortnight
 * apart are six ticks a fortnight wide, not seventy daily ones.
 *
 * The divisor and not the smallest gap: Vico's own `ChartEntryModel.xGcd` folds `gcdWith` over the
 * absolute gaps between neighbouring x values (`calculateXGcd`, Vico 1.13.1). Irregular dates whose
 * divisor is smaller than their closest pair therefore step finer than that pair, and this counts
 * them the same way.
 */
fun trendStepDays(dayOffsets: List<Int>): Int {
    var step = 0
    dayOffsets.forEach { step = greatestCommonDivisor(step, it) }
    return step.coerceAtLeast(1)
}

/**
 * How many positions such a chart's bottom axis has to label.
 *
 * Not the number of points: the axis steps in whole [trendStepDays], and a chart drawn against the
 * calendar has a tick at every step whether anything landed on it or not. Handed to
 * [threeLabelPlacer], which is the Progress screen's own rule for how many of them get a date (#63).
 */
fun trendAxisTicks(dayOffsets: List<Int>): Int {
    if (dayOffsets.isEmpty()) return 1
    return (dayOffsets.last() / trendStepDays(dayOffsets)) + 1
}

private tailrec fun greatestCommonDivisor(a: Int, b: Int): Int =
    if (b == 0) a else greatestCommonDivisor(b, a % b)
