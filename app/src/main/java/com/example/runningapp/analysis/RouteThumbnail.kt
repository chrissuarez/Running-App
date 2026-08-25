package com.example.runningapp.analysis

import com.example.runningapp.data.MeasuredTrack
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.recording.METERS_PER_DEGREE
import com.example.runningapp.recording.SessionRecorder
import kotlin.math.cos
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
 * Taken from the accuracy gate rather than picked, because the gate is what decides how far that
 * wander can reach: a fix is accepted at up to
 * [SessionRecorder.ACCURACY_THRESHOLD_METERS] of error, so two accepted fixes from a runner who
 * never moved can sit twice that apart — one wrong by the full amount in each direction. Anything
 * inside that width is ground the recording cannot swear the runner covered, and a route drawn
 * from it would be drawn from the error alone.
 *
 * It costs nothing worth keeping. Sixty metres of running has no shape to recognise a Run by, which
 * is the only thing the drawing is for.
 */
private val SHAPE_MINIMUM_METERS = 2 * SessionRecorder.ACCURACY_THRESHOLD_METERS

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
 * The same line with everything too small to see taken out of it.
 *
 * The thinning itself is [thinnedLineIndices], shared with the Run kept as a course
 * ([com.example.runningapp.routes.runAsCourse]); all that is decided here is how much detail a
 * thumbnail can show, which is [THUMBNAIL_DETAIL].
 */
private fun simplified(line: List<ThumbPoint>): List<ThumbPoint> {
    val kept = thinnedLineIndices(
        x = DoubleArray(line.size) { line[it].x.toDouble() },
        y = DoubleArray(line.size) { line[it].y.toDouble() },
        detail = THUMBNAIL_DETAIL,
    )
    return kept.map { line[it] }
}
