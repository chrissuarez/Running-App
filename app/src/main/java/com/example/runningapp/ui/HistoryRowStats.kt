package com.example.runningapp.ui

import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.averagePace
import com.example.runningapp.data.averagePaceText
import com.example.runningapp.data.inTargetZoneSeconds

/** One column of a History row: what it is called, and what it says. */
data class HistoryStat(val label: String, val value: String)

/** What a row shows where a number it does not have would go. */
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
 * Kilometres are left off the value and the row is titled `Dist`, since four columns and a route
 * square have to share a narrow screen; the Run's own page spells the unit out.
 */
fun historyRowStats(session: RunnerSession): List<HistoryStat> {
    val hasDistance = session.distanceKm > 0.0
    return listOf(
        HistoryStat("Dist", if (hasDistance) "%.2f".format(session.distanceKm) else NoNumber),
        // Derived, not read from the stored column (#163).
        HistoryStat("Pace", if (session.averagePace > 0.0) session.averagePaceText else NoNumber),
        HistoryStat("Avg HR", "${session.avgBpm}"),
        HistoryStat("Target", formatDuration(session.inTargetZoneSeconds)),
    )
}

/**
 * How much a stats column has to shrink to fit the space it was given — 1 when it already fits, and
 * never more than 1, so nothing is ever blown up to fill a gap.
 *
 * Four columns beside a 56dp square is a tight fit at the smallest screen the app supports, and at
 * an enlarged system text size it stops fitting altogether. Shrinking is the least-bad answer:
 * clipping or ellipsising would eat the two columns the order exists to make readable, and wrapping
 * would make every row a different height. What is shown at 1.3x text and 320dp is still larger than
 * the same row at ordinary text size, because the shrink is proportional to the overflow, not a
 * reset to the default.
 *
 * [availableWidth] arrives as [Int.MAX_VALUE] when the column is being measured with no width limit
 * at all; there is nothing to fit to, so nothing is shrunk.
 */
fun fitToWidthScale(contentWidth: Int, availableWidth: Int): Float {
    if (contentWidth <= 0 || availableWidth <= 0) return 1f
    if (availableWidth == Int.MAX_VALUE || contentWidth <= availableWidth) return 1f
    return availableWidth.toFloat() / contentWidth.toFloat()
}
