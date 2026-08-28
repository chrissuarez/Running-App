package com.example.runningapp.analysis

import com.example.runningapp.data.MeasuredTrack
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.recording.METERS_PER_DEGREE
import com.example.runningapp.recording.SessionRecorder
import kotlin.math.cos
import kotlin.math.max

/**
 * A route reduced to the shape of it, for the little drawing beside a Run in the History list (#51)
 * and beside a Route in the library (#59).
 *
 * A list's job is recognition, not inspection: a runner scrolling their history is asking "which
 * run was that?", a runner scrolling their routes is asking "which one is the park loop?", and the
 * answer to both is the shape — the river loop, the out-and-back up the hill, the
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
 * Pure, so what a list draws for any Run or Route can be settled without a phone.
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
 *
 * The same width is asked of a kept course (#59), on the second reason rather than the first: an
 * imported course is a drawn plan and carries no accuracy gate of its own, so what rules it out is
 * simply that sixty metres is nothing to recognise a Route by either. Both numbers would be the
 * same in any case — a course traced off a Run is made of those very fixes.
 */
private val SHAPE_MINIMUM_METERS = 2 * SessionRecorder.ACCURACY_THRESHOLD_METERS

/**
 * The shape of a Run's route, or null when there is none to draw.
 *
 * Null for a treadmill Run, a Run whose track has not loaded, a Run of fewer than two fixes, and a
 * Run that covered less ground than [SHAPE_MINIMUM_METERS]. The row then shows what it shows today
 * — no drawing, rather than a dot claiming to be a route.
 */
fun routeThumbnailOf(measured: MeasuredTrack): RouteThumbnail? =
    thumbnailOf(recordedStretches(measured).map { stretch -> stretch.map { it.shapePoint() } })

/**
 * A place on the line being drawn, which is all a drawing of it needs — no height, and no time.
 *
 * Deliberately neither a Track's point nor a Route's: a Run's Track and a kept course are different
 * things and must not be confused even in code (CONTEXT.md, **Route**), and this is the one thing
 * both have to offer a drawing. Each side turns its own points into these at its own door.
 */
data class ShapePoint(val latitude: Double, val longitude: Double)

/**
 * The shape of a Route the runner keeps, drawn beside it in the library (#59).
 *
 * The same drawing as a Run's, made the same way, because it answers the same question: a library
 * of names tells a runner nothing they cannot already read, and the shape is how they recognise the
 * park loop from the canal out-and-back at a glance.
 *
 * One stroke, always. A Run is cut at its Breaks because a straight line across ground nothing
 * witnessed would be a claim about where the runner went; a course claims nothing about where
 * anyone went, and by the time it is kept it is a single line either way — the GPX reader joins the
 * segments a file arrives in, and a Run kept as a course is thinned into one line before it is
 * stored.
 *
 * Null on the same terms as a Run's: fewer than two points, or less ground covered than
 * [SHAPE_MINIMUM_METERS]. Fewer than two is reachable here in a way it is not for a Run, because a
 * damaged row is read leniently and may give back almost nothing
 * ([com.example.runningapp.routes.RoutePolyline]); the row then keeps its empty square and its name
 * and numbers, which is more than a dot claiming to be a route would be worth.
 */
fun courseThumbnailOf(course: List<ShapePoint>): RouteThumbnail? = thumbnailOf(listOf(course))

/**
 * The shape shared by both drawings: the strokes fitted into the square, thinned, and centred.
 *
 * Strokes of fewer than two points are dropped before anything is measured — a line of one point
 * has no shape, and letting it through would let a course of one point set the span everything else
 * is scaled against.
 */
private fun thumbnailOf(strokes: List<List<ShapePoint>>): RouteThumbnail? {
    val lines = strokes.filter { it.size >= 2 }
    if (lines.isEmpty()) return null

    val fixes = lines.flatten()
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

    val drawn = lines.map { stretch ->
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

/** A recorded fix as a place on the line, dropping everything the Run knows about it. */
private fun TrackPoint.shapePoint() = ShapePoint(latitude, longitude)

/** The track cut at its breaks: the fixes of each stretch the recording covers. */
private fun recordedStretches(measured: MeasuredTrack): List<List<TrackPoint>> =
    measured.unbrokenLegs.map { unbroken -> measured.points.subList(unbroken.first, unbroken.last + 2) }

/**
 * The most points any one line is thinned from.
 *
 * The thinning walk is quadratic in the worst case — a line that keeps bending splits into two
 * halves that each keep bending — so the number of points handed to it has to be bounded by
 * something, and until #59 it was bounded by accident. A Run's Track is a fix every second or so
 * and an hour of running is a few thousand of them; an imported course is bounded by nothing but
 * the reader's refusal at 200,000 points
 * ([com.example.runningapp.routes.GpxRouteReader]), and nothing thins it before it is stored. One
 * such file, valid and accepted, would have held the whole library's drawings behind it on every
 * fresh process.
 *
 * Two thousand is far more than the square can show: at [THUMBNAIL_DETAIL] of a side, a 56dp square
 * has of the order of a hundred distinguishable steps across it, and what survives the thinning is
 * fewer again. So this bound is not a compromise on how the drawing looks — it is a bound on the
 * arithmetic, chosen well above anything the drawing could use.
 *
 * Read as the number of runs the line is cut into rather than as an exact ceiling: the ends and the
 * four corners of the box are kept on top of them, so a reduced line is this length give or take a
 * handful. What matters is that it does not grow with the file.
 */
private const val MOST_POINTS_A_DRAWING_IS_THINNED_FROM = 2_000

/**
 * The same line with everything too small to see taken out of it.
 *
 * The thinning itself is [thinnedLineIndices], shared with the Run kept as a course
 * ([com.example.runningapp.routes.runAsCourse]); all that is decided here is how much detail a
 * thumbnail can show, which is [THUMBNAIL_DETAIL].
 *
 * A line longer than [MOST_POINTS_A_DRAWING_IS_THINNED_FROM] is cut down to that first, by
 * [reducedToDrawableLength].
 */
private fun simplified(line: List<ThumbPoint>): List<ThumbPoint> {
    val sampled = reducedToDrawableLength(line)
    val kept = thinnedLineIndices(
        x = DoubleArray(sampled.size) { sampled[it].x.toDouble() },
        y = DoubleArray(sampled.size) { sampled[it].y.toDouble() },
        detail = THUMBNAIL_DETAIL,
    )
    return kept.map { sampled[it] }
}

/**
 * The line cut down to a length the thinning can take — see [MOST_POINTS_A_DRAWING_IS_THINNED_FROM].
 *
 * Counting alone will not do it. A course's points are not spread evenly along the ground: a GPX
 * may hold a hundred thousand points around a park and fifty for the kilometre of road out to it,
 * and a line cut down by index would drop that kilometre whole, between two samples, and draw a
 * route the runner never planned. Nothing after this can put it back — the thinning only ever
 * removes.
 *
 * So the line is cut into equal runs of points, and each run gives up the one point that reaches
 * furthest from where the run began. In a dense stretch that is much the same as counting; in a run
 * that holds a detour it is the far end of the detour, which is the whole of what that stretch has
 * to say. The four points the square is scaled against are kept outright on top of that, so the
 * drawing still fills its box in both directions however the file's points were spread.
 *
 * Both ends are always kept, so a loop still closes and a course still starts where the runner did.
 */
private fun reducedToDrawableLength(line: List<ThumbPoint>): List<ThumbPoint> {
    if (line.size <= MOST_POINTS_A_DRAWING_IS_THINNED_FROM) return line
    val runs = MOST_POINTS_A_DRAWING_IS_THINNED_FROM
    val last = line.lastIndex

    val kept = sortedSetOf(0, last)
    // The corners of the box the drawing is scaled into. Kept whatever run they fall in, because
    // the scaling has already promised the long side fills the square, and a sample that lost the
    // point the square was measured to would leave the drawing short of its own edge.
    kept += line.indices.minBy { line[it].x }
    kept += line.indices.maxBy { line[it].x }
    kept += line.indices.minBy { line[it].y }
    kept += line.indices.maxBy { line[it].y }

    for (run in 0 until runs) {
        val from = (run.toLong() * line.size / runs).toInt()
        val to = ((run + 1).toLong() * line.size / runs).toInt()
        if (to <= from) continue
        val start = line[from]
        kept += (from until to).maxBy { i ->
            val dx = line[i].x - start.x
            val dy = line[i].y - start.y
            dx * dx + dy * dy
        }
    }
    return kept.map { line[it] }
}
