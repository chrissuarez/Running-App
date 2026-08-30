package com.example.runningapp.routes

import com.example.runningapp.analysis.thinnedLineIndices
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.recording.METERS_PER_DEGREE
import com.example.runningapp.recording.degreesEastOf
import kotlin.math.cos

/**
 * How far a place may sit from the line drawn without it before the course keeps it (#55).
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
 * The course a walk went over, taken out of the places it was recorded at (#55).
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
     * what the Route's distance is measured along — and, being the row's `polyline`, it is the
     * Route's identity, which is why both doors into the library draw it here (#354).
     */
    val line: List<RoutePoint>,
    /**
     * The same walk before it was thinned: every place the Run recorded, in order.
     *
     * What the climb is worked out from, by both doors, because thinning is a judgement about where
     * the line *bends* and a hill is not a bend. A road running straight up one side of a hill and
     * down the other is two points once it is thinned, and its crest — the whole of the climb — is
     * one of the points thrown away. The heights never reach the row in any case ([RoutePolyline] keeps none),
     * so nothing is served by measuring them off the shortened line.
     */
    val asRecorded: List<RoutePoint>,
)

/**
 * What a Run's recorded track becomes when it is kept as a course (#55).
 *
 * The time is the whole of what this adds to [courseOf]: a Run's fixes carry the moment each one
 * arrived, and that is used to put them in the order the runner covered the ground and is then
 * dropped, a Route carrying no time at all ([RoutePoint]). Everything else a course is made of is
 * [courseOf]'s, and is shared with the file door for the reason argued there.
 */
fun runAsCourse(trackPoints: List<TrackPoint>): RunCourse = courseOf(
    trackPoints.sortedBy { it.timestampMillis }
        .map { RoutePoint(it.latitude, it.longitude, it.altitudeMeters) }
)

/**
 * The course a list of places describes: the one form every Route is stored in, whichever door it
 * came in by (#354).
 *
 * Two things happen to the places on the way, and each of them is about what a Route *is*:
 *
 *  - **A place recorded twice is one place.** A runner waiting at a crossing writes the same
 *    position down thirty times, and thirty copies of one point are thirty chances for anything
 *    reading the line to divide by a zero-length step.
 *  - **The line is thinned to its shape.** A straight road recorded once a second is a thousand
 *    fixes saying nothing a pair of them do not; see [ROUTE_DETAIL_METERS].
 *
 * **Both doors, and that is the point of it being one function.** A runner may save a Run as a
 * course here on its page (#55) and also share it as a GPX (#84) and hand that file back to the
 * library (#54) — one evening, two doors. The line is a Route's identity
 * ([com.example.runningapp.data.RouteDao.keepRoute]), so two doors drawing the line differently is
 * two rows of one course, which is what #354 found. They agree by being the same code, not by two
 * copies of a rule being kept in step.
 *
 * The price is named in [ADR 0014](../../../../../../../docs/adr/0014-a-route-is-a-plan-not-a-recording.md):
 * an imported Route's stored line is the file's shape rather than the file's every point, and its
 * banked distance is measured along the line that was kept. The file itself is not the thing being
 * preserved — a Route is a course to follow, and following it is what the line is for.
 *
 * What does *not* happen is a break. A Run's track is cut at every Pause and every Outage by
 * everything that draws or measures it, because the straight line over one is ground nothing
 * witnessed — but a Route makes no claim about ground covered. It is a course to follow, and the
 * runner who stopped for a coffee halfway round still went round. So a Run's stretches are joined
 * here exactly as a GPX file's segments are joined on the way in ([GpxRouteReader], ADR 0014).
 *
 * Pure and free of Android, so all of the above is pinned by `RunAsRouteTest` and
 * `OneRunOneRouteTest` rather than found on a phone.
 */
fun courseOf(points: List<RoutePoint>): RunCourse {
    val walked = points.withoutRepeatedPlaces()
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
    //
    // Every fix is placed by how far east it is of the first one rather than by its own longitude,
    // and that "how far east" is asked of [degreesEastOf] so that a Run over the date line is laid
    // out on the sheet the way it was run rather than flung most of the way round the world.
    val cosLatitude = cos(Math.toRadians(first().latitude))
    val kept = thinnedLineIndices(
        x = DoubleArray(size) {
            degreesEastOf(first().longitude, this[it].longitude) * METERS_PER_DEGREE * cosLatitude
        },
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
    // from the equator. Measured as how far east or west of the first fix the rest of them lie
    // ([degreesEastOf]) rather than by subtracting the smallest longitude from the largest: on the
    // date line the smallest and the largest are neighbours on the ground, and a runner standing
    // still with fixes landing either side of it would otherwise reach halfway round the world and
    // be kept as a course.
    val eastOfFirst = points.map { degreesEastOf(points.first().longitude, it.longitude) }
    val eastWest = (eastOfFirst.max() - eastOfFirst.min()) *
        METERS_PER_DEGREE * cos(Math.toRadians(points.first().latitude))
    return maxOf(northSouth, eastWest)
}
