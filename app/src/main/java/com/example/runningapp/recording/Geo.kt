package com.example.runningapp.recording

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Metres in a degree of latitude.
 *
 * The one place it is written down, because more than one thing in the app has to turn a span of
 * degrees into a span of ground: the drawing beside a Run in History sizes itself by it
 * ([com.example.runningapp.analysis.routeThumbnailOf]), and a Run kept as a course is thinned in
 * metres ([com.example.runningapp.routes.runAsCourse]). It is a rounding of the ellipsoid
 * above and never a substitute for [geodesicDistanceMeters], which is what every distance a runner
 * is shown is measured with.
 */
const val METERS_PER_DEGREE = 111_320.0

// WGS84 ellipsoid semi-major/semi-minor axes, meters.
private const val WGS84_SEMI_MAJOR_AXIS = 6378137.0
private const val WGS84_SEMI_MINOR_AXIS = 6356752.3142

/**
 * Vincenty's inverse formula for geodesic distance on the WGS84 ellipsoid — a port of
 * android.location.Location.distanceTo()'s computeDistanceAndBearing (AOSP, Apache 2.0), so pure
 * Kotlin reproduces the platform's distance numbers exactly rather than approximating them with a
 * spherical (haversine) formula.
 *
 * The one distance function in the app: [SessionRecorder] measures a run's legs with it as they
 * arrive, and [com.example.runningapp.data.measureMovingTimeSeconds] re-measures a finished run's track
 * with it afterwards. Both must agree, or a run's distance and its moving time would disagree
 * about the same two points.
 */
fun geodesicDistanceMeters(
    fromLatitude: Double,
    fromLongitude: Double,
    toLatitude: Double,
    toLongitude: Double,
): Double {
    val lat1 = Math.toRadians(fromLatitude)
    val lat2 = Math.toRadians(toLatitude)
    val lon1 = Math.toRadians(fromLongitude)
    val lon2 = Math.toRadians(toLongitude)

    val a = WGS84_SEMI_MAJOR_AXIS
    val b = WGS84_SEMI_MINOR_AXIS
    val f = (a - b) / a
    val aSqMinusBSqOverBSq = (a * a - b * b) / (b * b)

    val l = lon2 - lon1
    var bigA = 0.0
    val u1 = atan((1.0 - f) * tan(lat1))
    val u2 = atan((1.0 - f) * tan(lat2))

    val cosU1 = cos(u1)
    val cosU2 = cos(u2)
    val sinU1 = sin(u1)
    val sinU2 = sin(u2)
    val cosU1cosU2 = cosU1 * cosU2
    val sinU1sinU2 = sinU1 * sinU2

    var sigma = 0.0
    var deltaSigma = 0.0
    var lambda = l

    for (iter in 0 until 20) {
        val lambdaOrig = lambda
        val cosLambda = cos(lambda)
        val sinLambda = sin(lambda)
        val t1 = cosU2 * sinLambda
        val t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
        val sinSqSigma = t1 * t1 + t2 * t2
        val sinSigma = sqrt(sinSqSigma)
        val cosSigma = sinU1sinU2 + cosU1cosU2 * cosLambda
        sigma = atan2(sinSigma, cosSigma)
        val sinAlpha = if (sinSigma == 0.0) 0.0 else cosU1cosU2 * sinLambda / sinSigma
        val cosSqAlpha = 1.0 - sinAlpha * sinAlpha
        val cos2Sm = if (cosSqAlpha == 0.0) 0.0 else cosSigma - 2.0 * sinU1sinU2 / cosSqAlpha

        val uSquared = cosSqAlpha * aSqMinusBSqOverBSq
        bigA = 1 + (uSquared / 16384.0) *
            (4096.0 + uSquared * (-768 + uSquared * (320.0 - 175.0 * uSquared)))
        val bigB = (uSquared / 1024.0) *
            (256.0 + uSquared * (-128.0 + uSquared * (74.0 - 47.0 * uSquared)))
        val cos2SmSq = cos2Sm * cos2Sm
        deltaSigma = bigB * sinSigma *
            (cos2Sm + (bigB / 4.0) *
                (cosSigma * (-1.0 + 2.0 * cos2SmSq) -
                    (bigB / 6.0) * cos2Sm *
                    (-3.0 + 4.0 * sinSigma * sinSigma) *
                    (-3.0 + 4.0 * cos2SmSq)))

        val bigC = (f / 16.0) * cosSqAlpha * (4.0 + f * (4.0 - 3.0 * cosSqAlpha))
        lambda = l + (1.0 - bigC) * f * sinAlpha *
            (sigma + bigC * sinSigma *
                (cos2Sm + bigC * cosSigma * (-1.0 + 2.0 * cos2Sm * cos2Sm)))

        val delta = (lambda - lambdaOrig) / lambda
        if (abs(delta) < 1.0e-12) break
    }

    // Location.distanceTo() returns a float; round-trip through Float to match its precision exactly.
    return (b * bigA * (sigma - deltaSigma)).toFloat().toDouble()
}

/**
 * How far east of [referenceLongitude] a fix at [longitude] sits, in degrees, going the shorter way
 * round.
 *
 * Longitude is the one coordinate that runs out. It climbs to 180° and then, without the ground
 * changing at all, starts again at -180°, so two fixes a stride apart either side of that line are
 * written down 359.9998° apart. Anything that measures east-west by subtracting one longitude from
 * another — the width of a scatter, the sideways place of a fix on a flat sheet — reads that stride
 * as most of the way round the world unless it is asked the question this answers: not "what is the
 * difference between these two numbers" but "which way, and how far, would you walk".
 *
 * The answer is wrapped into (-180°, +180°]: west of the reference is negative, east is positive,
 * and the far side of the planet is called east. That last is arbitrary and never matters, because
 * the only things asking are measuring one Run's worth of ground, which is a few kilometres of it.
 *
 * A companion to [METERS_PER_DEGREE] and used with it: multiply this by that and by the cosine of
 * the latitude to get metres east.
 */
fun degreesEastOf(referenceLongitude: Double, longitude: Double): Double {
    val difference = (longitude - referenceLongitude) % 360.0
    return when {
        difference > 180.0 -> difference - 360.0
        difference <= -180.0 -> difference + 360.0
        else -> difference
    }
}
