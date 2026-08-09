package com.example.runningapp.routes

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
 * How much of the course each height is averaged over before it is judged a climb.
 *
 * The same rule as a Run's, restated on the only axis a Route has. A Run smooths a GPS height over
 * five seconds ([com.example.runningapp.analysis.elevationOf]) because noise arrives at a rate; a
 * Route has no times at all — a planned one was never run — so it cannot ask that question. Fifteen
 * metres is those five seconds at the one-fix-per-second and running pace the tracks that number was
 * measured on were recorded at.
 *
 * Measuring along the ground rather than counting fixes is what makes one number right for both
 * kinds of file. A track exported at 1 Hz folds about five points together, as its Run would; a
 * planned route with a point every hundred metres folds none, which is correct — its heights come
 * off a terrain model rather than an instrument, and there is no jitter in them to remove.
 */
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
    if (points.none { it.elevationMeters != null }) return null

    val heights = points.map { it.elevationMeters }.filledFromNeighbours()
        .smoothedAlong(points, ROUTE_SMOOTHING_METERS)

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

/** The gaps taken from the nearest height either side, so the list stays one entry per point. */
private fun List<Double?>.filledFromNeighbours(): List<Double> {
    val filled = toMutableList()
    var lastKnown: Double? = null
    for (i in indices) {
        if (filled[i] != null) lastKnown = filled[i] else filled[i] = lastKnown
    }
    // Anything before the first stated height has nothing behind it to take, so it takes the one
    // ahead. Never null by here: a course with no stated height at all never reaches this.
    val firstKnown = filled.firstOrNull { it != null } ?: 0.0
    return filled.map { it ?: firstKnown }
}

/**
 * Each height averaged with every height within half of [windowMeters] of it along the course.
 *
 * Centred, and shortened at the ends rather than dropping the first and last points: a route keeps a
 * height everywhere it has a point. The walk is linear — the points are in course order, so both
 * edges of the window only ever move forwards.
 */
private fun List<Double>.smoothedAlong(points: List<RoutePoint>, windowMeters: Double): List<Double> {
    if (isEmpty()) return this
    val half = windowMeters / 2
    val distanceTo = DoubleArray(size)
    for (i in 1 until size) {
        distanceTo[i] = distanceTo[i - 1] + geodesicDistanceMeters(
            points[i - 1].latitude,
            points[i - 1].longitude,
            points[i].latitude,
            points[i].longitude,
        )
    }
    var from = 0
    var to = 0
    // The window always holds at least point i: the near edge stops there, and the far edge is
    // dragged up to it by a gap of zero or less, so neither can leave it behind.
    var runningTotal = first()
    return indices.map { i ->
        while (distanceTo[i] - distanceTo[from] > half) {
            runningTotal -= this[from]
            from++
        }
        while (to < lastIndex && distanceTo[to + 1] - distanceTo[i] <= half) {
            to++
            runningTotal += this[to]
        }
        runningTotal / (to - from + 1)
    }
}
