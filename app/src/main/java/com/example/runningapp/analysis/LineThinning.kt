package com.example.runningapp.analysis

import kotlin.math.hypot

/**
 * The points of a line worth keeping: everything that bends it further than [detail] from the line
 * that would be drawn without it (Ramer-Douglas-Peucker).
 *
 * One walk, in one place, because the app thins a line for two different reasons and both have to
 * keep the same shape. The drawing beside a Run in History throws away everything too small to see
 * ([routeThumbnailOf]); a Run kept as a course throws away everything too small to follow
 * ([com.example.runningapp.routes.runAsCourse]). Only the tolerance and the units differ, and
 * written twice one copy would eventually be fixed and the other left — the same argument
 * [ADR 0014](../../../../../../../docs/adr/0014-a-route-is-a-plan-not-a-recording.md) makes for
 * sharing the fill-from-neighbours rule with a Run's elevation.
 *
 * [x] and [y] are the same line in whatever flat units the caller is thinking in — a fraction of a
 * thumbnail's side, metres on the ground — and [detail] is in those units. The ends are always kept.
 *
 * Walked with a stack rather than by recursion: an hour's Run is thousands of fixes, and a track
 * recorded straight down a road is the case that puts every one of them on the call stack.
 */
fun thinnedLineIndices(x: DoubleArray, y: DoubleArray, detail: Double): List<Int> {
    require(x.size == y.size) { "A line needs an x for every y" }
    if (x.size <= 2) return x.indices.toList()

    val keep = BooleanArray(x.size)
    keep[0] = true
    keep[x.lastIndex] = true

    val pending = ArrayDeque<Pair<Int, Int>>()
    pending += 0 to x.lastIndex
    while (pending.isNotEmpty()) {
        val (from, to) = pending.removeLast()
        if (to - from < 2) continue
        var furthest = -1
        var furthestDistance = detail
        for (i in from + 1 until to) {
            val distance = distanceToStretch(x[i], y[i], x[from], y[from], x[to], y[to])
            if (distance > furthestDistance) {
                furthest = i
                furthestDistance = distance
            }
        }
        if (furthest < 0) continue
        keep[furthest] = true
        pending += from to furthest
        pending += furthest to to
    }
    return x.indices.filter { keep[it] }
}

/**
 * How far a point sits from the stretch of line actually drawn between two others — from whichever
 * end it lies beyond, when it lies beyond one of them.
 *
 * Measured from the drawn stretch rather than from the endless line it sits on, because an
 * out-and-back that turns for home before it gets back to where it started is the case that tells
 * the two apart. Two kilometres out and one back leaves the turnaround a kilometre past the finish
 * and *exactly on* the line through start and finish: measured against that line it is nothing
 * worth keeping, both halves collapse, and the run is drawn as a one-kilometre stroll in a straight
 * line — the wrong shape, whether it is being glanced at in a list or followed on the ground.
 * Measured against the stretch, the turnaround is a kilometre from the nearer end, and it survives.
 */
private fun distanceToStretch(
    x: Double,
    y: Double,
    fromX: Double,
    fromY: Double,
    toX: Double,
    toY: Double,
): Double {
    val runX = toX - fromX
    val runY = toY - fromY
    val lengthSquared = runX * runX + runY * runY
    val fromStartX = x - fromX
    val fromStartY = y - fromY
    if (lengthSquared == 0.0) return hypot(fromStartX, fromStartY)
    // How far along the stretch the point sits, as a fraction of it — held inside the two ends, so
    // anything past either one is measured from that end rather than from open ground beyond it.
    val along = ((fromStartX * runX + fromStartY * runY) / lengthSquared).coerceIn(0.0, 1.0)
    return hypot(fromStartX - along * runX, fromStartY - along * runY)
}
