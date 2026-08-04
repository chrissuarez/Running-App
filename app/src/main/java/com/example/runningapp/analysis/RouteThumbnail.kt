package com.example.runningapp.analysis

import com.example.runningapp.data.MeasuredTrack
import com.example.runningapp.data.TrackPoint
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * The Run's route reduced to the shape of it, for the little drawing beside a Run in the History
 * list (#51).
 *
 * The list's job is recognition, not inspection: a runner scrolling their history is asking "which
 * run was that?", and the answer is the shape — the river loop, the out-and-back up the hill, the
 * park laps. So this is the outline and nothing else. No map underneath, no zone colours: at forty
 * pixels a side, streets are a smudge and five colours are a smear, and both cost the list the one
 * thing it must keep, which is scrolling smoothly. The Run's own page has the map that answers the
 * harder questions ([TrackMap]).
 *
 * Held in a square of its own, from (0,0) at the top left to (1,1) at the bottom right, so whatever
 * draws it only has to know how big it wants to be. The route's own proportions are kept inside
 * that square — a long out-and-back stays a long out-and-back rather than being stretched to fill
 * the corners — and it is centred in whichever direction it has room to spare in.
 *
 * Pure, so what the list draws for any Run can be settled without a phone.
 */
data class RouteThumbnail(
    /**
     * The route as lines to draw, each one a stretch the recording actually covers.
     *
     * More than one where the Run paused or lost signal: the same break rule the Run's own map is
     * cut at ([trackMapOf]), because a straight line across ground nothing witnessed would make an
     * out-and-back look like a loop in the one view that is read at a glance.
     */
    val strokes: List<List<ThumbPoint>>,
)

/** A place on the route, as a fraction of the square the thumbnail is drawn in. */
data class ThumbPoint(val x: Float, val y: Float)

/**
 * How far a fix may sit from the line drawn without it before it has to be drawn — as a fraction of
 * the thumbnail's own side.
 *
 * A hundredth of a square that is a thumb-tip wide is well under a pixel, so nothing dropped here
 * could have been seen. What it saves is the drawing: an hour's Run is three thousand fixes, twenty
 * of those Runs are on the screen at once, and drawing sixty thousand line segments a frame is how
 * a list starts to stutter. Corners survive it regardless of how much straight running surrounds
 * them — that is the point of measuring from the line rather than from the last fix kept.
 */
private const val THUMBNAIL_DETAIL = 0.01

/**
 * How much ground a Run has to cover before its route is a shape rather than a scatter.
 *
 * A Run that never left the spot does not stand perfectly still on the record: fixes accepted at
 * the read-side accuracy gate wander tens of metres around a runner who never moved, and the
 * thumbnail scales whatever span it is given to fill the square. So a phone left on a desk, or a
 * Run stopped in the first seconds, would be drawn as a confident scribble across the row —
 * the most eye-catching thing in the list, and pure noise.
 *
 * Fifty metres, because that is under the width of the ground a stationary fix can wander over and
 * well under any distance whose shape a runner would recognise. Anything smaller is drawn as
 * nothing at all, which is the honest answer.
 */
private const val SHAPE_MINIMUM_METERS = 50.0

/** Metres in a degree of latitude — the span is measured in degrees and the minimum in metres. */
private const val METERS_PER_DEGREE = 111_320.0

/**
 * The shape of a Run's route, or null when there is none to draw.
 *
 * Null for a treadmill Run, a Run whose track has not loaded, a Run of fewer than two fixes, and a
 * Run that covered less ground than [SHAPE_MINIMUM_METERS]. The row then shows what it shows today
 * — no drawing, rather than a dot claiming to be a route.
 */
fun routeThumbnailOf(measured: MeasuredTrack): RouteThumbnail? {
    val strokes = recordedStretches(measured)
    if (strokes.isEmpty()) return null

    val fixes = strokes.flatten()
    // East-west is measured in the same metres as north-south, by shrinking a degree of longitude
    // the way the latitude does: a degree of longitude is half a degree of latitude in Norway, and
    // read as the same unit it would squash every route there flat.
    val eastWest = cos(Math.toRadians(fixes.first().latitude))
    val westmost = fixes.minOf { it.longitude } * eastWest
    val eastmost = fixes.maxOf { it.longitude } * eastWest
    val southmost = fixes.minOf { it.latitude }
    val northmost = fixes.maxOf { it.latitude }
    val spanX = eastmost - westmost
    val spanY = northmost - southmost
    val span = max(spanX, spanY)
    if (span * METERS_PER_DEGREE < SHAPE_MINIMUM_METERS) return null

    // The long side fills the square; the short one keeps its proportion and is centred in the room
    // that leaves.
    val scale = 1.0 / span
    val sidePadding = (1.0 - spanX * scale) / 2.0
    val topAndBottomPadding = (1.0 - spanY * scale) / 2.0

    val drawn = strokes.map { stretch ->
        stretch.map { fix ->
            ThumbPoint(
                x = (sidePadding + (fix.longitude * eastWest - westmost) * scale).toFloat(),
                // Flipped, because a thumbnail's y grows downwards and north is up.
                y = (1.0 - topAndBottomPadding - (fix.latitude - southmost) * scale).toFloat(),
            )
        }
    }.map(::simplified).filter { it.size >= 2 }

    return if (drawn.isEmpty()) null else RouteThumbnail(drawn)
}

/** The track cut at its breaks: the fixes of each stretch the recording covers. */
private fun recordedStretches(measured: MeasuredTrack): List<List<TrackPoint>> =
    measured.unbrokenLegs.map { unbroken -> measured.points.subList(unbroken.first, unbroken.last + 2) }

/**
 * The same line with everything too small to see taken out of it (Ramer-Douglas-Peucker).
 *
 * Keeps the fix furthest from the straight line between the two ends, and asks the same of each
 * half, until no fix left out sits further than [THUMBNAIL_DETAIL] from the line that would be
 * drawn without it. A mile of straight road collapses to its two ends; the corner it turns at
 * survives exactly where it was.
 *
 * Walked with a stack rather than by recursion: an hour's Run is thousands of fixes, and a track
 * recorded straight down a road is the case that puts every one of them on the call stack.
 */
private fun simplified(line: List<ThumbPoint>): List<ThumbPoint> {
    if (line.size <= 2) return line
    val keep = BooleanArray(line.size)
    keep[0] = true
    keep[line.lastIndex] = true

    val pending = ArrayDeque<Pair<Int, Int>>()
    pending += 0 to line.lastIndex
    while (pending.isNotEmpty()) {
        val (from, to) = pending.removeLast()
        if (to - from < 2) continue
        var furthest = -1
        var furthestDistance = THUMBNAIL_DETAIL
        for (i in from + 1 until to) {
            val distance = distanceToLine(line[i], line[from], line[to])
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
    return line.filterIndexed { i, _ -> keep[i] }
}

/**
 * How far [point] sits from the stretch of line actually drawn between [start] and [end] — from
 * whichever end it lies beyond, when it lies beyond one of them.
 *
 * Measured from the drawn stretch rather than from the endless line it sits on, because an
 * out-and-back that turns for home before it gets back to where it started is the case that tells
 * the two apart. Two kilometres out and one back leaves the turnaround a kilometre past the finish
 * and *exactly on* the line through start and finish: measured against that line it is nothing
 * worth keeping, both halves collapse, and the run is drawn as a one-kilometre stroll in a straight
 * line — the wrong shape, in the one view that is read at a glance. Measured against the stretch,
 * the turnaround is a kilometre from the nearer end, and it survives.
 */
private fun distanceToLine(point: ThumbPoint, start: ThumbPoint, end: ThumbPoint): Double {
    val dx = (end.x - start.x).toDouble()
    val dy = (end.y - start.y).toDouble()
    val lengthSquared = dx * dx + dy * dy
    val fromStartX = (point.x - start.x).toDouble()
    val fromStartY = (point.y - start.y).toDouble()
    if (lengthSquared == 0.0) return hypot(fromStartX, fromStartY)
    // How far along the stretch the point sits, as a fraction of it — held inside the two ends, so
    // anything past either one is measured from that end rather than from open ground beyond it.
    val along = ((fromStartX * dx + fromStartY * dy) / lengthSquared).coerceIn(0.0, 1.0)
    return hypot(fromStartX - along * dx, fromStartY - along * dy)
}
