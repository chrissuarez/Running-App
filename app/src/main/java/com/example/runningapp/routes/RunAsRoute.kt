package com.example.runningapp.routes

import com.example.runningapp.analysis.thinnedLineIndices
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.recording.METERS_PER_DEGREE
import kotlin.math.cos

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

/**
 * The course a Run went over, taken out of what it recorded (#55).
 *
 * Two readings of one walk, because the Route's line and the Route's climb are not answered by the
 * same points — see [line] and [asRecorded].
 */
data class RunCourse(
    /**
     * The line the Route keeps: the fixes in order, a place recorded twice written once, and the
     * whole thinned to its shape ([ROUTE_DETAIL_METERS]).
     *
     * This is what is written into the row and what a runner following the Route covers, so it is
     * what the Route's distance is measured along.
     */
    val line: List<RoutePoint>,
    /**
     * The same walk before it was thinned: every place the Run recorded, in order.
     *
     * What the climb is worked out from, because thinning is a judgement about where the line
     * *bends* and a hill is not a bend. A road running straight up one side of a hill and down the
     * other is two points once it is thinned, and its crest — the whole of the climb — is one of the
     * points thrown away. The heights never reach the row in any case ([RoutePolyline] keeps none),
     * so nothing is served by measuring them off the shortened line.
     */
    val asRecorded: List<RoutePoint>,
)

/**
 * What a Run's recorded track becomes when it is kept as a course (#55).
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
 * Pure and free of Android, so all of the above is pinned by `RunAsRouteTest` rather than found on a
 * phone.
 */
fun runAsCourse(trackPoints: List<TrackPoint>): RunCourse {
    val walked = trackPoints
        .sortedBy { it.timestampMillis }
        .map { RoutePoint(it.latitude, it.longitude, it.altitudeMeters) }
        .withoutRepeatedPlaces()
    return RunCourse(line = walked.thinnedToItsShape(), asRecorded = walked)
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
 * The same line with everything finer than [ROUTE_DETAIL_METERS] taken out of it.
 *
 * The thinning itself is [thinnedLineIndices], shared with the drawing beside a Run in History; all
 * that is decided here is what the line is measured in, which is metres on the ground.
 */
private fun List<RoutePoint>.thinnedToItsShape(): List<RoutePoint> {
    if (size <= 2) return this
    // Metres on a flat sheet, taken once for the whole line. A degree of longitude shrinks going
    // north, so it is shrunk by the cosine of where the Run was — one Run covers too little ground
    // for that to have changed within it, and this is only ever asked how far a fix sits from a
    // line a few hundred metres long.
    val cosLatitude = cos(Math.toRadians(first().latitude))
    val kept = thinnedLineIndices(
        x = DoubleArray(size) { (this[it].longitude - first().longitude) * METERS_PER_DEGREE * cosLatitude },
        y = DoubleArray(size) { (this[it].latitude - first().latitude) * METERS_PER_DEGREE },
        detail = ROUTE_DETAIL_METERS,
    )
    return kept.map { this[it] }
}

/**
 * How far the course reaches across the ground: the wider of its two sides, north-south or
 * east-west.
 *
 * How far it *reaches*, not how far it goes. A runner standing on one spot for ten minutes with a
 * fix arriving every second records hundreds of metres of wandering, all of it inside the error of
 * the fixes it is made of, so the length of that line says nothing about whether there is a course
 * in it. What its extent says is plain: a scatter thirty metres wide is a scatter however long its
 * path. The same question the History drawing asks of a Run before it draws it
 * ([com.example.runningapp.analysis.routeThumbnailOf]), asked here before one is kept.
 */
fun courseSpanMeters(points: List<RoutePoint>): Double {
    if (points.isEmpty()) return 0.0
    val northSouth = (points.maxOf { it.latitude } - points.minOf { it.latitude }) * METERS_PER_DEGREE
    // East-west in the same metres, by shrinking a degree of longitude to the width it has this far
    // from the equator.
    val eastWest = (points.maxOf { it.longitude } - points.minOf { it.longitude }) *
        METERS_PER_DEGREE * cos(Math.toRadians(points.first().latitude))
    return maxOf(northSouth, eastWest)
}
