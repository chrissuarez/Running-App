package com.example.runningapp.routes

import com.example.runningapp.analysis.filledFromNeighbours
import com.example.runningapp.recording.geodesicDistanceMeters

/**
 * How far the runner must climb above the last low point before it is banked as gain.
 *
 * The GPS tier's ten metres, and it is the only tier a Route has: a GPX file carries a height per
 * point and never says where it came from, so the reader has to assume the noisier of the two
 * instruments. Ten metres is roughly one standard deviation of a GPS fix's vertical error — see
 * [com.example.runningapp.analysis.elevationOf], where the same number is argued at length (#20).
 */
private const val ROUTE_HYSTERESIS_METERS = 10.0

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
 * Nothing here breaks the course into stretches, unlike a Run's elevation, which refuses to bank a
 * climb across a Break. A Route has no Breaks — the segments a file arrives in were joined on the
 * way in ([GpxRouteReader]) — so the whole of it is one climb to walk.
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
