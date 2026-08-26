package com.example.runningapp.recording

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

// Flat geometry over a small patch of ground: metres east and north of one origin, and the two
// questions a line asks of a place once it is in them. See [LocalFrame] for what the flattening is
// for and what it costs.

/** Below which a length is nothing: two fixes at the same place, or a leg of no ground at all. */
internal const val FLAT_EPSILON_METERS = 1e-6

/** A place, in metres east and north of wherever the frame was pinned. */
internal data class FlatPoint(val east: Double, val north: Double) {

    fun metersTo(other: FlatPoint): Double = hypot(other.east - east, other.north - north)

    /** How far along this-to-[other] the point nearest [target] sits, as a fraction of the leg. */
    fun fractionNearest(other: FlatPoint, target: FlatPoint): Double {
        val runEast = other.east - east
        val runNorth = other.north - north
        val lengthSquared = runEast * runEast + runNorth * runNorth
        if (lengthSquared <= FLAT_EPSILON_METERS * FLAT_EPSILON_METERS) return 0.0
        val projected = ((target.east - east) * runEast + (target.north - north) * runNorth) / lengthSquared
        return projected.coerceIn(0.0, 1.0)
    }

    fun along(other: FlatPoint, fraction: Double): FlatPoint =
        FlatPoint(east + (other.east - east) * fraction, north + (other.north - north) * fraction)
}

/**
 * A difference of longitude, signed and taken **the short way round** — so ground on the date line
 * is not read as half a planet wide.
 *
 * Two fixes a stride apart either side of ±180° subtract to 359.99°, and every reader of a
 * difference of longitude has to say which way round it meant. This is where the app says it: the
 * projection a Segment's gate is measured in ([LocalFrame]), and the interpolation a Run's
 * waypoints are taken by ([com.example.runningapp.segments.runShapeOf], #73).
 */
fun theShortWayRound(degrees: Double): Double {
    var short = degrees
    while (short > 180.0) short -= 360.0
    while (short < -180.0) short += 360.0
    return short
}

/**
 * Metres east and north of one origin, so ground-matching is done in flat geometry.
 *
 * The app has one distance function ([geodesicDistanceMeters]) and it answers "how far apart are
 * these two places". What it cannot answer is "where on this line is that place" — projecting a
 * point onto a line has no cheap answer on an ellipsoid, and it is the question every piece of
 * ground-matching in the app is made of: whether a Run crossed a Segment's gate (#70), and how far
 * along a Route the runner has got (#57). So the ground is flattened first, and the flattening is
 * written down once here rather than at each of them.
 *
 * The scaling is taken at the origin on the WGS84 ellipsoid rather than on a sphere, so the two ways
 * this app measures ground ([geodesicDistanceMeters]) do not disagree by the third of a percent a
 * spherical earth would cost.
 *
 * What is deliberately *not* written down here is how big a patch a caller may flatten. The error
 * grows with distance from the origin, so every caller has to say where it pins the frame and why
 * that is small enough: [com.example.runningapp.segments.segmentTraversalsIn] pins one frame at a
 * Segment's start, a Segment being a few hundred metres of one street, while
 * [com.example.runningapp.routes.CourseLine] pins a frame per leg of a course, a Route being
 * kilometres long.
 */
internal class LocalFrame(private val originLatitude: Double, private val originLongitude: Double) {

    private val metersPerDegreeLatitude: Double
    private val metersPerDegreeLongitude: Double

    init {
        val latitude = originLatitude * PI / 180.0
        val a = WGS84_SEMI_MAJOR_AXIS
        val eccentricitySquared = 1.0 - (WGS84_SEMI_MINOR_AXIS / a) * (WGS84_SEMI_MINOR_AXIS / a)
        val w = 1.0 - eccentricitySquared * sin(latitude) * sin(latitude)
        // The meridional and normal radii of curvature at this latitude — how far a degree of
        // latitude and a degree of longitude are on the ground here.
        metersPerDegreeLatitude = PI / 180.0 * a * (1.0 - eccentricitySquared) / (w * sqrt(w))
        metersPerDegreeLongitude = PI / 180.0 * a / sqrt(w) * cos(latitude)
    }

    fun project(latitude: Double, longitude: Double): FlatPoint = FlatPoint(
        east = degreesOfLongitudeFromOrigin(longitude) * metersPerDegreeLongitude,
        north = (latitude - originLatitude) * metersPerDegreeLatitude,
    )

    /** Signed, and the short way round, so ground on the date line is not half a planet wide. */
    private fun degreesOfLongitudeFromOrigin(longitude: Double): Double =
        theShortWayRound(longitude - originLongitude)

    private companion object {
        // The same ellipsoid [geodesicDistanceMeters] measures on.
        const val WGS84_SEMI_MAJOR_AXIS = 6378137.0
        const val WGS84_SEMI_MINOR_AXIS = 6356752.3142
    }
}
