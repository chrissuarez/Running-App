package com.example.runningapp.ui

import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.averagePace
import com.example.runningapp.data.averagePaceText
import com.example.runningapp.data.inTargetZoneSeconds

/** One column of a History row: what it is called, and what it says. */
data class HistoryStat(val label: String, val value: String)

/**
 * What a row shows where a number it does not have would go.
 *
 * One dash for any missing number, rather than the `--:--` a missing pace is written as on the Run's
 * own page: down four narrow columns what is being read is the shape of the column, and a dash the
 * width of a time would read as a number that happens to be blanked.
 */
private const val NoNumber = "--"

/**
 * The four numbers a History row shows, in the order they are read (#232).
 *
 * Distance and pace lead, because what is being watched over months is that the distance is getting
 * longer and the pace is improving; heart rate and time in target are checked afterwards.
 *
 * Always four, on every Run, treadmill and outdoor alike — a row that dropped the columns it had no
 * number for would put distance and pace in a different place on every row, and the whole point of
 * the order is being able to run an eye straight down those two columns. A Run with no distance
 * says so with a dash, which is also the invitation to open it and state one (see **Stated
 * Distance** in `CONTEXT.md`).
 *
 * The dash is on the distance, not on the kind of Run: an outdoor Run whose GPS recorded nothing
 * reads exactly like a treadmill Run nobody stated a distance for, because that is what both are.
 *
 * Kilometres are left off the value and the column is titled `Dist`, since four columns and a
 * 56dp square have to share a narrow screen; the Run's own page spells the unit out.
 */
fun historyRowStats(run: RunnerSession): List<HistoryStat> {
    val hasDistance = run.distanceKm > 0.0
    return listOf(
        HistoryStat("Dist", if (hasDistance) "%.2f".format(run.distanceKm) else NoNumber),
        // Derived, not read from the stored column (#163).
        HistoryStat("Pace", if (run.averagePace > 0.0) run.averagePaceText else NoNumber),
        HistoryStat("Avg HR", "${run.avgBpm}"),
        HistoryStat("Target", formatDuration(run.inTargetZoneSeconds)),
    )
}

/**
 * How wide one of the stats columns is: the row's width shared equally between them, minus the gaps.
 *
 * Equally, rather than each column taking what its own numbers need, because what is read is the
 * column and not the row — a distance that moved left or right depending on how long that Run's
 * pace happened to be would cost the reorder the thing it was for.
 *
 * [rowWidth] arrives as [Int.MAX_VALUE] when the row is being measured with no width limit at all,
 * and is handed straight back: there is no width to share out yet.
 */
fun statColumnWidth(rowWidth: Int, gapWidth: Int, columns: Int): Int {
    if (columns <= 0 || rowWidth == Int.MAX_VALUE) return Int.MAX_VALUE
    val gaps = gapWidth.coerceAtLeast(0) * (columns - 1)
    return ((rowWidth - gaps) / columns).coerceAtLeast(0)
}

/**
 * How much the stats have to shrink to fit the columns they were given — 1 when they already fit,
 * and never more than 1, so nothing is ever blown up to fill a gap.
 *
 * Four columns beside a 56dp square is a tight fit at the smallest screen the app supports, and at
 * an enlarged system text size it stops fitting altogether. Shrinking is the least-bad answer:
 * clipping or ellipsising would eat the two columns the order exists to make readable, and wrapping
 * would make every row a different height. It is the whole row that shrinks together, by the amount
 * its widest number needs, so the four columns stay one size and the numbers stay on one line
 * across the row.
 *
 * [availableWidth] arrives as [Int.MAX_VALUE] when the row is being measured with no width limit at
 * all; there is nothing to fit to, so nothing is shrunk.
 */
fun fitToWidthScale(contentWidth: Int, availableWidth: Int): Float {
    if (contentWidth <= 0 || availableWidth <= 0) return 1f
    if (availableWidth == Int.MAX_VALUE || contentWidth <= availableWidth) return 1f
    return availableWidth.toFloat() / contentWidth.toFloat()
}
