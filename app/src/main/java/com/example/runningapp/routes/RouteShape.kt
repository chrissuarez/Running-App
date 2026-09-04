package com.example.runningapp.routes

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.analysis.ShapePoint
import com.example.runningapp.analysis.filledFromNeighbours
import com.example.runningapp.recording.geodesicDistanceMeters
import com.example.runningapp.segments.RUN_SHAPE_MINIMUM_METERS
import com.example.runningapp.segments.RunShape
import com.example.runningapp.segments.shapeAlong

/**
 * How far the runner must climb above the last low point before it is banked as gain.
 *
 * Three metres, because ten was not a threshold but a ruler with one mark on it (#419). Gain is
 * banked in whole steps of this number, so at ten metres the smallest reportable climb was ten
 * metres and every rolling route in the library read exactly `10 m up` — the same figure for a flat
 * canal path and for a hill. Three metres reports the hill.
 *
 * It is deliberately below the GPS tier's ten metres, which [com.example.runningapp.analysis.elevationOf]
 * still uses for a Run (#20), and the two are answering different questions. That ten metres is one
 * standard deviation of a *single* GPS fix's vertical error, and a Run bands raw fixes. A Route's
 * heights are smoothed first, by [smoothedAlong], which folds each height into at least five of its
 * neighbours; what reaches this rule is a mean, whose error is several times smaller than a fix's.
 * Measured on the nine tracks exported from this phone, three metres over that smoothing reports
 * between twelve and forty-seven metres of climbing on runs of two to six kilometres — a spread
 * that follows the ground, where ten metres reported ten on almost every one of them.
 *
 * **What this number does not cover.** A five-point mean over a file that alternates by some amount
 * a point leaves about a fifth of that amount behind, so this rule protects the reading only from a
 * per-point wobble of *under* fifteen metres. At fifteen the residual is three exactly, and the
 * comparison below is `>=`, so it banks. A file noisier than that banks its noise as climbing, over
 * and over, all route long — the #20 defect, reached at a lower noise floor than ten metres reached
 * it. That is a known, accepted limit rather than an oversight: nothing this phone or a route
 * builder exports comes close to it, and widening the window instead is no fix, because killing a
 * twenty-metre alternation with a plain mean takes eleven points, which on a route whose points sit
 * a hundred metres apart averages over a kilometre and rubs real hills out with the noise. #424
 * holds the proper fix; `RouteShapeTest.a wobble the smoothing cannot absorb is still banked` pins
 * the limit in the meantime.
 */
private const val ROUTE_HYSTERESIS_METERS = 3.0

/**
 * How many points each height is averaged with before it is judged a climb, and how much ground the
 * window is widened to cover when they sit closer together than that.
 *
 * The same rule as a Run's, restated on the axes a Route has. A Run smooths a GPS height over five
 * seconds ([com.example.runningapp.analysis.elevationOf]) because noise arrives at a rate; a Route
 * has no times at all — a planned one was never run — so it cannot ask that question. Five points is
 * those five seconds at the one-fix-per-second those tracks were recorded at, and fifteen metres is
 * the ground a runner covers in them.
 *
 * Both rules, and the wider of the two wins, because a GPX arrives at either of two densities and
 * each rule alone gets one of them wrong:
 *
 *  - **Five points alone** over-smooths a file recorded far more often than once a second, folding
 *    real ground away. The ground rule stretches the window back out to the fifteen metres the noise
 *    actually lives in.
 *  - **Fifteen metres alone** stops smoothing altogether the moment a file's points are more than
 *    seven metres apart, which is most exported tracks: Strava and Komoot simplify the *positions*
 *    they export and keep the heights as recorded, so a simplified track is exactly the jittery case
 *    #20 exists for and would have had none of its jitter removed. The point rule keeps five heights
 *    folded together however far apart the file put them.
 *
 * The cost is that a widely spaced route has the shoulders of a real hill shaved off it, a five-point
 * mean over five hundred metres of ground being a great deal of smoothing. That is the right way to
 * be wrong: under-reporting a climb by its shoulders costs a metre or two, while banking jitter
 * reports hundreds of metres of climbing that never happened — the defect #20 exists to close.
 */
private const val ROUTE_SMOOTHING_POINTS = 5
private const val ROUTE_SMOOTHING_METERS = 15.0

/**
 * The course as places on a line, for the drawing beside it in the library (#59).
 *
 * Here rather than at the screen that wants it, so the one place a Route's points become a shape is
 * beside the other things worked out from those points.
 */
fun List<RoutePoint>.asShape(): List<ShapePoint> = map { ShapePoint(it.latitude, it.longitude) }

/** How far the course goes: every step of it, first point to last (#54). */
fun routeDistanceMeters(points: List<RoutePoint>): Double =
    points.zipWithNext { from, to ->
        geodesicDistanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
    }.sum()

/**
 * How much climbing the course holds, or null when the file gave nothing to work it out from (#54).
 *
 * Null rather than zero, and the two are different things a runner needs told apart: a flat route
 * climbs nothing, while a file with no `<ele>` in it is silent about a route that may well go over a
 * hill. The screen says so in words rather than printing a nought.
 *
 * Nothing here breaks the route into stretches, unlike a Run's elevation, which refuses to bank a
 * climb across a Break. A Route has no Breaks — the segments a file arrives in were joined on the
 * way in ([GpxRouteReader]) — so the whole of it is one climb to walk
 * ([ADR 0014](../../../../../../../docs/adr/0014-a-route-is-a-plan-not-a-recording.md)).
 */
fun routeElevationGainMeters(points: List<RoutePoint>): Double? {
    if (points.size < 2) return null
    // Two stated heights, not one. A single height says how high one point is, which is not
    // something climbed between — spread over the rest of the course it makes a flat line and
    // reports "0 m up", telling the runner a route the file said nothing about is level.
    if (points.count { it.elevationMeters != null } < 2) return null

    val heights = points.map { it.elevationMeters }.filledFromNeighbours()
        .smoothedAlong(points, ROUTE_SMOOTHING_POINTS, ROUTE_SMOOTHING_METERS)

    var gain = 0.0
    var trough = heights.first()
    for (height in heights) {
        if (height < trough) trough = height
        if (height - trough >= ROUTE_HYSTERESIS_METERS) {
            gain += height - trough
            trough = height
        }
    }
    return gain
}

/**
 * Each height averaged with its neighbours: [windowPoints] of them, or every height within half of
 * [windowMeters] along the route, whichever reaches further.
 *
 * Centred, and shortened at the ends rather than dropping the first and last points: a Route keeps a
 * height everywhere it has a point. The walk is linear in the number of points — each rule gives
 * bounds that only ever move forwards, so the wider of the two does too, and the window's total is
 * carried along rather than re-added at every step.
 */
private fun List<Double>.smoothedAlong(
    points: List<RoutePoint>,
    windowPoints: Int,
    windowMeters: Double,
): List<Double> {
    if (isEmpty()) return this
    val halfMeters = windowMeters / 2
    val halfPoints = windowPoints / 2
    val distanceTo = DoubleArray(size)
    for (i in 1 until size) {
        distanceTo[i] = distanceTo[i - 1] + geodesicDistanceMeters(
            points[i - 1].latitude,
            points[i - 1].longitude,
            points[i].latitude,
            points[i].longitude,
        )
    }
    // Where the ground rule alone would reach, tracked apart from the window itself so the wider of
    // the two rules can be taken without either edge ever having to go back.
    var groundFrom = 0
    var groundTo = 0
    var from = 0
    var to = 0
    // The window always holds at least point i: both rules bracket it, so neither edge leaves it.
    var runningTotal = first()
    return indices.map { i ->
        while (distanceTo[i] - distanceTo[groundFrom] > halfMeters) groundFrom++
        while (groundTo < lastIndex && distanceTo[groundTo + 1] - distanceTo[i] <= halfMeters) groundTo++
        val reachesBackTo = minOf(groundFrom, (i - halfPoints).coerceAtLeast(0))
        val reachesOnTo = maxOf(groundTo, (i + halfPoints).coerceAtMost(lastIndex))
        while (from < reachesBackTo) {
            runningTotal -= this[from]
            from++
        }
        while (to < reachesOnTo) {
            to++
            runningTotal += this[to]
        }
        runningTotal / (to - from + 1)
    }
}

/**
 * The course reduced to the waypoints a Run is recognised by, or null where there is not enough
 * line to reduce (#74).
 *
 * The very same sampler a Run's own shape is taken with ([shapeAlong]) over the very same legs the
 * course's distance is counted from ([routeDistanceMeters]) — one arithmetic rather than two. That
 * is the whole of what makes a Route's page and a Run's page able to agree about which Runs covered
 * this ground: the comparison at the end of it ([runsMatch]) is written for two shapes taken the
 * same way, and two samplers that agreed today would be free to drift apart at the next change to
 * either.
 *
 * Null for a course of fewer than two points, and for one under [RUN_SHAPE_MINIMUM_METERS] — a
 * course too short to hold a route worth recognising, on the Run side's own floor and for its
 * reason. Such a course simply claims no Runs; its remembered ones are unaffected, because those
 * were written down rather than recognised.
 *
 * Heights are not read. Two lines over the same ground are the same route whether the file that drew
 * one carried `<ele>` and the file that drew the other did not.
 */
fun routeShapeOf(points: List<RoutePoint>): RunShape? = shapeAlong(
    places = points.map { MapFix(it.latitude, it.longitude) },
    legMeters = points.zipWithNext { from, to ->
        geodesicDistanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
    },
)
