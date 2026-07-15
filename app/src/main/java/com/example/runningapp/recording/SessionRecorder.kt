package com.example.runningapp.recording

import java.util.LinkedList
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float?,
    val timestampMs: Long,
)

fun interface Clock {
    fun nowMillis(): Long
}

data class SessionRecorderMetrics(
    val distanceKm: Double,
    val paceMinPerKm: Double,
    val lastFix: LocationFix?,
)

/**
 * Pure Kotlin session recording logic (distance, pace, split cues) extracted from
 * [com.example.runningapp.LocationTracker]. Fed a stream of [LocationFix]es by an
 * Android host; has no framework dependencies so it can be exercised on the JVM.
 */
class SessionRecorder(
    private val clock: Clock,
    private val playSplitCue: (String) -> Unit,
    private val isSplitAnnouncementsEnabled: () -> Boolean,
    private val onMetricsUpdated: (SessionRecorderMetrics) -> Unit,
    private val logDecision: (reason: String, detail: String) -> Unit = { _, _ -> },
) {
    private var lastFix: LocationFix? = null
    // The distance-delta baseline. Deliberately only ever an *accepted* fix, so accumulated
    // distance always equals the sum of accepted-to-accepted legs - the same thing a map drawn
    // from SessionRepository.getTrackPointsForMap()'s filtered points would show (#38 review).
    private var lastAcceptedFix: LocationFix? = null
    private var sessionDistanceMeters = 0.0
    private var lastSplitAnnouncedKm = 0
    private val paceHistory = LinkedList<Pair<Long, Double>>()

    fun reset() {
        sessionDistanceMeters = 0.0
        lastSplitAnnouncedKm = 0
        synchronized(paceHistory) { paceHistory.clear() }
        lastFix = null
        lastAcceptedFix = null
        onMetricsUpdated(SessionRecorderMetrics(0.0, 0.0, null))
    }

    /** Clears the fix used as the distance-delta baseline without losing accumulated distance/pace state. */
    fun discardLastFix() {
        lastFix = null
        lastAcceptedFix = null
    }

    fun getDistanceKm(): Double = sessionDistanceMeters / 1000.0

    fun getPaceMinPerKm(): Double = calculatePace()

    fun onLocationFix(fix: LocationFix) {
        val now = clock.nowMillis()
        val accepted = isAccuracyAccepted(fix.accuracyMeters)

        var speedMps = 0.0
        if (accepted) {
            lastAcceptedFix?.let { last ->
                val distance = distanceBetweenMeters(last, fix)
                val timeDeltaSec = (now - last.timestampMs) / 1000.0
                sessionDistanceMeters += distance
                logDecision("distance_updated", "+${"%.2f".format(distance)}m, total=${"%.2f".format(sessionDistanceMeters)}m")

                speedMps = if (fix.speedMps != null && fix.speedMps > 0.1f) {
                    fix.speedMps.toDouble()
                } else if (timeDeltaSec > 0.5) {
                    distance / timeDeltaSec
                } else {
                    0.0
                }
            }
            lastAcceptedFix = fix
        } else {
            logDecision("location_rejected", "accuracy=${fix.accuracyMeters}m > threshold=${ACCURACY_THRESHOLD_METERS}m")
        }
        lastFix = fix

        synchronized(paceHistory) {
            if (accepted) {
                paceHistory.add(Pair(now, if (speedMps > 0.2) speedMps else 0.0))
            }
            // Prune on every fix, not just accepted ones - otherwise a run of rejected fixes
            // leaves a stale sample sitting past the pace window instead of aging out (#38 review).
            while (paceHistory.isNotEmpty() && (now - paceHistory.first.first > PACE_WINDOW_MS)) {
                paceHistory.removeFirst()
            }
        }

        if (accepted) {
            val currentKm = (sessionDistanceMeters / 1000).toInt()
            if (isSplitAnnouncementsEnabled() && currentKm > lastSplitAnnouncedKm) {
                lastSplitAnnouncedKm = currentKm
                val pace = calculatePace()
                if (pace > 0) {
                    val paceMins = pace.toInt()
                    val paceSecs = ((pace - paceMins) * 60).roundToInt()
                    playSplitCue("Split $currentKm kilometer. Pace $paceMins minutes $paceSecs seconds per kilometer.")
                } else {
                    playSplitCue("Split $currentKm kilometer.")
                }
            }
        }

        val currentDistanceKm = sessionDistanceMeters / 1000.0
        val currentPace = calculatePace()
        logDecision("state_update", "distanceKm=${"%.3f".format(currentDistanceKm)} paceMinPerKm=${"%.2f".format(currentPace)}")
        onMetricsUpdated(SessionRecorderMetrics(currentDistanceKm, currentPace, lastFix))
    }

    private fun calculatePace(): Double {
        synchronized(paceHistory) {
            if (paceHistory.isEmpty()) return 0.0
            val avgSpeedMps = paceHistory.map { it.second }.average()
            if (avgSpeedMps <= 0.1) return 0.0
            return 1000.0 / (avgSpeedMps * 60.0)
        }
    }

    companion object {
        /**
         * GPS fixes coarser than this are excluded from distance, pace, and split cues (#38), and
         * the same bar gates which stored [com.example.runningapp.data.TrackPoint]s a map query
         * returns, so what the runner hears mid-run matches what they see afterward.
         */
        const val ACCURACY_THRESHOLD_METERS = 30.0

        /** Whether a fix this accurate counts toward distance/pace/split cues or a map read (#38). */
        fun isAccuracyAccepted(accuracyMeters: Float): Boolean = accuracyMeters <= ACCURACY_THRESHOLD_METERS

        private const val PACE_WINDOW_MS = 15_000L

        // WGS84 ellipsoid semi-major/semi-minor axes, meters.
        private const val WGS84_SEMI_MAJOR_AXIS = 6378137.0
        private const val WGS84_SEMI_MINOR_AXIS = 6356752.3142

        /**
         * Vincenty's inverse formula for geodesic distance on the WGS84 ellipsoid — a port of
         * android.location.Location.distanceTo()'s computeDistanceAndBearing (AOSP, Apache 2.0),
         * so this pure-Kotlin recorder reproduces the original service's distance numbers exactly
         * rather than approximating them with a spherical (haversine) formula.
         */
        private fun distanceBetweenMeters(from: LocationFix, to: LocationFix): Double {
            val lat1 = Math.toRadians(from.latitude)
            val lat2 = Math.toRadians(to.latitude)
            val lon1 = Math.toRadians(from.longitude)
            val lon2 = Math.toRadians(to.longitude)

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
    }
}
