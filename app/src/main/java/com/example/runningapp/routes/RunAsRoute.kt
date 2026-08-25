package com.example.runningapp.routes

import com.example.runningapp.data.TrackPoint
import kotlin.math.cos
import kotlin.math.hypot

/**
 * How far a fix may sit from the line drawn without it before the course keeps it (#55).
 *
 * Two metres, which is finer than any corner a path actually turns and coarser than the wandering
 * of a fix standing still. It is not the accuracy gate: every fix reaching here has already passed
 * that ([com.example.runningapp.data.SessionRepository.getTrackPointsForMap]), and the question
 * this answers is a different one — not "is this fix believable" but "does the line bend here". A
 * road run straight for a mile bends nowhere and is two points; the corner at the end of it moves
 * not at all.
 *
 * Kept small on purpose. A Route is a line to follow rather than a picture to glance at, so this is
 * a fraction of the [com.example.runningapp.analysis.routeThumbnailOf] detail, which is drawn a
 * centimetre wide on a History row and can throw away far more.
 */
private const val ROUTE_DETAIL_METERS = 2.0

/** Metres in a degree of latitude — the fixes are in degrees and the detail above is in metres. */
private const val METERS_PER_DEGREE = 111_320.0

/**
 * The course a Run went over, taken out of what it recorded (#55).
 *
 * Three things happen to the track on the way, and each of them is about what a Route *is*:
 *
 *  - **Time orders it, and then goes.** A Route carries no time — it may be run again tomorrow or
 *    never — so the stamps are used to put the fixes in the order the runner covered them and are
 *    then dropped ([RoutePoint]).
 *  - **A place recorded twice is one place.** A runner waiting at a crossing writes the same
 *    position down thirty times, and thirty copies of one point are thirty chances for anything
 *    reading the line to divide by a zero-length step.
 *  - **The line is thinned to its shape.** A straight road recorded once a second is a thousand
 *    fixes saying nothing a pair of them do not; see [ROUTE_DETAIL_METERS].
 *
 * What does *not* happen is a break. A Run's track is cut at every Pause and every Outage by
 * everything that draws or measures it, because the straight line over one is ground nothing
 * witnessed — but a Route makes no claim about ground covered. It is a course to follow, and the
 * runner who stopped for a coffee halfway round still went round. So the stretches are joined here
 * exactly as a GPX file's segments are joined on the way in ([GpxRouteReader],
 * [ADR 0014](../../../../../../../docs/adr/0014-a-route-is-a-plan-not-a-recording.md)).
 *
 * Pure and free of Android, so all of the above is pinned by [RunAsRouteTest] rather than found on a
 * phone.
 */
fun runAsRoutePoints(trackPoints: List<TrackPoint>): List<RoutePoint> {
    val walked = trackPoints
        .sortedBy { it.timestampMillis }
        .map { RoutePoint(it.latitude, it.longitude, it.altitudeMeters) }
        .withoutRepeatedPlaces()
    return if (walked.size <= 2) walked else walked.thinnedToItsShape()
}

/**
 * The same line with a place recorded twice in a row written once.
 *
 * The first of each run of them is the one kept, so a height that arrived on the second copy is
 * lost. That is the right way round: the fix that first reached the place is the one the rest of
 * the recording is hung on, and a height is a nicety a course can do without — the whole climb is
 * banked from what survives, and one repeat of one point cannot move it.
 */
private fun List<RoutePoint>.withoutRepeatedPlaces(): List<RoutePoint> = filterIndexed { i, point ->
    i == 0 || point.latitude != this[i - 1].latitude || point.longitude != this[i - 1].longitude
}

/**
 * The same line with everything finer than [ROUTE_DETAIL_METERS] taken out of it
 * (Ramer-Douglas-Peucker).
 *
 * Keeps the fix furthest from the straight line between the two ends, and asks the same of each
 * half, until nothing left out sits further than that from the line that would be drawn without it.
 *
 * Distance is measured to the stretch actually drawn rather than to the endless line through its
 * ends, for the reason [com.example.runningapp.analysis.routeThumbnailOf] gives at length: an
 * out-and-back that turns for home short of where it started leaves the turnaround sitting exactly
 * on that endless line, and measured against it the whole route collapses to a straight stroll.
 *
 * Walked with a stack rather than by recursion: an hour's Run is thousands of fixes, and a track
 * recorded straight down a road is the case that puts every one of them on the call stack.
 */
private fun List<RoutePoint>.thinnedToItsShape(): List<RoutePoint> {
    // Metres on a flat sheet, taken once for the whole line. A degree of longitude shrinks going
    // north, so it is shrunk by the cosine of where the Run was — one Run covers too little ground
    // for that to have changed within it, and this is only ever asked how far a fix sits from a
    // line a few hundred metres long.
    val cosLatitude = cos(Math.toRadians(first().latitude))
    val x = DoubleArray(size) { (this[it].longitude - first().longitude) * METERS_PER_DEGREE * cosLatitude }
    val y = DoubleArray(size) { (this[it].latitude - first().latitude) * METERS_PER_DEGREE }

    val keep = BooleanArray(size)
    keep[0] = true
    keep[lastIndex] = true

    val pending = ArrayDeque<Pair<Int, Int>>()
    pending += 0 to lastIndex
    while (pending.isNotEmpty()) {
        val (from, to) = pending.removeLast()
        if (to - from < 2) continue
        var furthest = -1
        var furthestDistance = ROUTE_DETAIL_METERS
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
    return filterIndexed { i, _ -> keep[i] }
}

/** How far (x, y) sits from the stretch of line between its two ends — from an end, when it lies beyond one. */
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
    if (lengthSquared == 0.0) return hypot(x - fromX, y - fromY)
    val alongIt = (((x - fromX) * runX + (y - fromY) * runY) / lengthSquared).coerceIn(0.0, 1.0)
    return hypot(x - (fromX + alongIt * runX), y - (fromY + alongIt * runY))
}
