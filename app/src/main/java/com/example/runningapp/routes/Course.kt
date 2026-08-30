package com.example.runningapp.routes

import com.example.runningapp.analysis.thinnedLineIndices
import com.example.runningapp.data.Route
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.recording.METERS_PER_DEGREE
import com.example.runningapp.recording.degreesEastOf
import kotlin.math.cos

/**
 * How far a place may sit from the line drawn without it before the course keeps it (#55, #354).
 *
 * Two metres, which is finer than any corner a path actually turns and coarser than the wandering
 * of a fix standing still. It is not an accuracy gate, and neither door reaching it asks it to be
 * one: a Run's fixes have already passed that gate
 * ([com.example.runningapp.data.SessionRepository.getTrackPointsForMap]), and a file's places have
 * no accuracy to gate on — a GPX states a position and nothing about how well it was known
 * ([GpxRouteReader]). The question this answers is a different one in both cases — not "is this
 * place believable" but "does the line bend here". A road run straight for a mile bends nowhere and
 * is two points; the corner at the end of it moves not at all.
 *
 * Kept small on purpose. A Route is a line to follow rather than a picture to glance at, so this is
 * a fraction of the [com.example.runningapp.analysis.routeThumbnailOf] detail, which is drawn a
 * centimetre wide on a History row and can throw away far more.
 */
private const val ROUTE_DETAIL_METERS = 2.0

/**
 * The course a walk went over, taken out of the places it was recorded at (#55, #354).
 *
 * Two readings of one walk, because the Route's line and the Route's climb are not answered by the
 * same points — see [line] and [asRecorded].
 *
 * No Run in the name and none in the type: since #354 this is what a GPX file's places become as
 * well as what a Run's track becomes, the two doors into the library sharing the one form a Route
 * is stored in.
 */
data class Course(
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
     * The same walk before it was thinned: every place that was recorded, in order — a Run's fixes,
     * or a file's points.
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
fun runAsCourse(trackPoints: List<TrackPoint>): Course = courseOf(
    trackPoints.sortedBy { it.timestampMillis }
        .map { RoutePoint(it.latitude, it.longitude, it.altitudeMeters) }
)

/**
 * The course a list of places describes: the one form every Route is stored in, whichever door it
 * came in by (#354).
 *
 * Three things happen to the places on the way, and each of them is about what a Route *is*:
 *
 *  - **Every place is moved to where the row will keep it.** Seven decimal places, a little over a
 *    centimetre, and it happens first because everything below it asks a question whose answer
 *    changes in that centimetre — see [RoutePolyline.snapped].
 *  - **A place recorded twice is one place.** A runner waiting at a crossing writes the same
 *    position down thirty times, and thirty copies of one point are thirty chances for anything
 *    reading the line to divide by a zero-length step. Two fixes a millimetre apart are one place
 *    too, once the snapping above has written them down.
 *  - **The line is thinned to its shape.** A straight road recorded once a second is a thousand
 *    fixes saying nothing a pair of them do not; see [ROUTE_DETAIL_METERS].
 *
 * **Both doors, and that is the point of it being one function.** A runner may save a Run as a
 * course here on its page (#55) and also share it as a GPX (#84) and hand that file back to the
 * library (#54) — one evening, two doors. The line is a Route's identity
 * ([com.example.runningapp.data.RouteDao.keepRoute]), so two doors drawing the line differently is
 * two rows of one course, which is what #354 found. They agree by being the same code fed the same
 * numbers — the shaping shared here, and the numbers made identical by [RoutePolyline.snapped]
 * before any of it is asked — rather than by two copies of a rule being kept in step.
 *
 * The *line* is what agrees. The climb is not part of it: a file states its heights to a tenth of a
 * metre and a Run holds them as it measured them, so a Run's own GPX handed back re-measures the
 * Route that Run saved rather than being waved through untouched. That is the remedy ADR 0014 names
 * for a banked number, working as intended — one row, its numbers refreshed from the file.
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
 * Pure and free of Android, so all of the above is pinned by `CourseTest` and
 * `OneRunOneRouteTest` rather than found on a phone.
 */
fun courseOf(points: List<RoutePoint>): Course {
    // Snapped before anything is asked of the line, so the two doors are thinning the very same
    // numbers rather than numbers a centimetre apart — see [RoutePolyline.snapped], which is where
    // that last centimetre is argued.
    val walked = RoutePolyline.snapped(points).withoutRepeatedPlaces()
    return Course(line = walked.thinnedToItsShape(), asRecorded = walked)
}

/**
 * The row this course is kept as, under [name] and marked with where it came from (#354).
 *
 * Which reading of the walk feeds which number is decided here and nowhere else, for the reason the
 * shaping itself is decided in [courseOf]: the two doors into the library must not be able to drift
 * apart, and a rule written twice is a rule that will eventually be changed once. So both doors hand
 * over a course and a name, and only the things that genuinely differ between them — what the course
 * is called, where it came from, and whether it may re-measure a Route already kept — stay theirs.
 *
 * The distance is along the line that was kept, and the climb is up the hills that were recorded.
 * The two are measured off different readings of the walk, and [Course] is where that is argued.
 */
fun Course.asRoute(name: String, createdAtMillis: Long, source: String): Route = Route(
    name = name,
    distanceMeters = routeDistanceMeters(line),
    elevationGainMeters = routeElevationGainMeters(asRecorded),
    polyline = RoutePolyline.encode(line),
    createdAtMillis = createdAtMillis,
    source = source,
)

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
 * The most points one course's line is thinned from.
 *
 * The thinning walk is quadratic in the worst case — a line that keeps bending splits into a stretch
 * of one point and a stretch of the rest — so the number of points handed to it has to be bounded by
 * something, and on this side it was bounded by accident until #354. A Run's own track bounds itself
 * at about a fix a second; a file's points are bounded by nothing but the reader's refusal at
 * 200,000 ([GpxRouteReader]), and before #354 no thinning was asked of them at all. Sharing the
 * shaping between the two doors is what brought a file's every point to this walk, so the bound
 * arrives with it.
 *
 * Twenty thousand is far above anything anyone runs. An hour at a fix a second is some 3,600 places
 * and a marathon some 15,000, so no course a runner recorded or drew reaches this, and the bound
 * changes no result they will ever see. What it buys is an end to the arithmetic: two hundred
 * million distance measurements at the very worst rather than twenty billion, a second or so of one
 * import rather than an import worker that never finishes.
 *
 * It is deliberately not the [com.example.runningapp.analysis.routeThumbnailOf] bound of two
 * thousand, though the rule below is that one's: a thumbnail is a smudge on a History row and can
 * throw away almost everything, while a course is a line to follow. The bound is the same idea at
 * the size this side of the app works at.
 */
private const val MOST_POINTS_A_COURSE_IS_THINNED_FROM = 20_000

/**
 * The same line with everything finer than [ROUTE_DETAIL_METERS] taken out of it.
 *
 * The thinning itself is [thinnedLineIndices], shared with the drawing beside a Run in History; all
 * that is decided here is what the line is measured in, which is metres on the ground.
 *
 * A line longer than [MOST_POINTS_A_COURSE_IS_THINNED_FROM] is shortened first by
 * [spacedOutEnoughToThin], which drops only places the thinning was already allowed to lose. This
 * lives here, in the shaping both doors go through, rather than at the file door where the long
 * lines come from: the line is a Route's identity, so a bound on one door alone would mean a long
 * enough course thinning differently depending on which door it came in by, and the library keeping
 * it twice — the very fault #354 fixes. A bound that is part of the shape must be part of the
 * shaping.
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
    val x = DoubleArray(size) {
        degreesEastOf(first().longitude, this[it].longitude) * METERS_PER_DEGREE * cosLatitude
    }
    val y = DoubleArray(size) { (this[it].latitude - first().latitude) * METERS_PER_DEGREE }

    // Shortened first if it is too long to thin, and only then. Everything below reads the line
    // through [thinnable], which is every place there is whenever the line was short enough — so a
    // course anyone actually recorded is thinned from exactly the places it always was.
    val thinnable = spacedOutEnoughToThin(x, y)
    val kept = thinnedLineIndices(
        x = DoubleArray(thinnable.size) { x[thinnable[it]] },
        y = DoubleArray(thinnable.size) { y[thinnable[it]] },
        detail = ROUTE_DETAIL_METERS,
    )
    return kept.map { this[thinnable[it]] }
}

/**
 * The places of the line, with the ones the thinning was already allowed to lose taken out of it,
 * and no others — and only when there are more of them than [MOST_POINTS_A_COURSE_IS_THINNED_FROM].
 *
 * The rule is [com.example.runningapp.analysis.routeThumbnailOf]'s, and deliberately so: it is the
 * only rule that shortens a line without drawing a different one, and the argument for it is made in
 * full at `spacedOutEnoughToThin` there. In short, a line cannot be shortened by counting — a course
 * may spend a hundred thousand places shuffling round a park and fifty on the kilometre of road out
 * to it, and every rule that picks places by their position in the list throws that kilometre away.
 * So the only place dropped is one sitting within [gap] of the last place kept, which moves the line
 * by less than [gap] and so cannot lose a feature bigger than [gap], however the file spread its
 * points. Both ends are always kept.
 *
 * What differs here is the unit and the starting gap. The thumbnail measures in fractions of its own
 * square; this measures in metres on the flat sheet the thinning itself is laid out on, and starts
 * at [ROUTE_DETAIL_METERS]. That start gives away nothing: a place dropped for sitting within two
 * metres of the last one kept moves the line by less than the detail the thinning is already
 * entitled to throw away, so at the starting gap this pre-pass can only remove what the walk it
 * feeds could have removed anyway.
 *
 * Wider gaps are a different bargain, and are only ever reached by a line that is still tens of
 * thousands of places long once everything within two metres of its neighbour has gone — a scribble
 * built to be dense rather than a course anyone ran or drew. Widening is the honest answer to that:
 * it keeps the scribble's shape to within the gap it settled at instead of never finishing.
 */
private fun spacedOutEnoughToThin(x: DoubleArray, y: DoubleArray): List<Int> {
    if (x.size <= MOST_POINTS_A_COURSE_IS_THINNED_FROM) return x.indices.toList()

    // Doubled until the line is short enough, each pass one walk down it. It gets there in a handful
    // of passes — a gap doubling from two metres is a kilometre wide within nine — and it ends
    // whatever it is handed: each doubling at least halves what a stretch of ground can hold, and a
    // gap wide enough that nothing clears it leaves the two ends, which is two. So the bound is this
    // loop's post-condition rather than something the caller has to check again — the thumbnail
    // checks a second time, and there is nothing left there for the check to catch either.
    var gap = ROUTE_DETAIL_METERS
    var kept = separatedByAtLeast(x, y, gap)
    while (kept.size > MOST_POINTS_A_COURSE_IS_THINNED_FROM) {
        gap *= 2
        kept = separatedByAtLeast(x, y, gap)
    }
    return kept
}

/** The line walked once, keeping each place that lands at least [gap] from the last one kept. */
private fun separatedByAtLeast(x: DoubleArray, y: DoubleArray, gap: Double): List<Int> {
    val kept = ArrayList<Int>()
    var last = 0
    kept += last
    for (i in x.indices) {
        val dx = x[i] - x[last]
        val dy = y[i] - y[last]
        if (dx * dx + dy * dy >= gap * gap) {
            kept += i
            last = i
        }
    }
    // The far end is kept whether or not it earned its place, so the course still ends where the
    // runner did rather than at whichever place last cleared the gap.
    if (kept.last() != x.lastIndex) kept += x.lastIndex
    return kept
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
