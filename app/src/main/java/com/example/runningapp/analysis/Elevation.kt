package com.example.runningapp.analysis

import com.example.runningapp.data.MeasuredTrack
import com.example.runningapp.data.TrackPoint
import kotlin.math.pow

/** The pressure the standard atmosphere has at sea level, hPa — what a height is measured from. */
private const val STANDARD_PRESSURE_HPA = 1013.25

/**
 * How much the runner must climb above the last low point before it is banked as gain — Strava's
 * own two numbers, one per tier.
 *
 * They are far apart because a threshold has to sit above the noise of the instrument that fed it.
 * Two metres is several times a barometer's precision; ten metres is roughly one standard deviation
 * of a GPS fix's vertical error. Below its threshold, a height wobbling between 98 m and 102 m on
 * flat ground would be read as climbing four metres over and over, all run long.
 */
private const val BAROMETER_HYSTERESIS_METERS = 2.0

/** See [BAROMETER_HYSTERESIS_METERS] — the same rule, at what a GPS fix's vertical error demands. */
private const val GPS_HYSTERESIS_METERS = 10.0

/**
 * How many fixes each tier averages a height over before judging it a climb.
 *
 * Five for GPS is the width found to reproduce what Strava and Garmin Connect make of the same
 * track. Three for the barometer is not about noise but about weather: a gust of wind is a real
 * pressure change that is not a change in height, and it passes in a second or two. Wider would
 * start flattening the short sharp climbs the barometer is here to catch.
 */
private const val GPS_SMOOTHING_FIXES = 5
private const val BAROMETER_SMOOTHING_FIXES = 3

/**
 * How wrong a fix may say its own height is before it is passed over. The research settles that
 * there must be a cutoff without naming one; twenty metres is where a fix stops carrying information
 * a five-fix average could rescue, given that a fix states this as a one-sigma bound and a third of
 * them are worse than they claim. The last trusted height stands in, rather than the fix being
 * dropped, so the heights stay one-to-one with the track's points.
 */
private const val GPS_VERTICAL_ACCURACY_LIMIT_METERS = 20f

/**
 * How high the runner was at each fix of [track], and how far they must climb for it to count —
 * or null when the run recorded nothing to derive a height from (#20, #45).
 *
 * Two tiers, which is what both Garmin and Strava do and for the same reason: a phone barometer
 * measures a *change* in height to within about a quarter of a metre, while a GPS fix is out by
 * eight to fifteen metres vertically — and gain is a sum of positive differences, so every metre of
 * noise adds. Summed raw, a flat route reports hundreds of metres of climbing.
 *
 * The full reasoning, with sources, is in `docs/research/elevation-gain.md`. Two things that
 * research recommends are deliberately absent, because this reads a *finished* run rather than
 * driving a live altimeter:
 *
 * - **No fusion with GPS altitude to correct the barometer's absolute offset.** That offset is the
 *   sea-level pressure of the day, and it cancels out of every difference. Gain never asks how high
 *   the runner was, only how much higher than a moment ago.
 * - **No temperature correction.** Same reason: it scales the whole run's heights together.
 *
 * Null covers a treadmill run, and covers the backfilled breadcrumbs of runs recorded before the app
 * kept a track at all: those rows carry a position and nothing else.
 */
internal fun elevationOf(track: MeasuredTrack): ElevationProfile? {
    val points = track.points
    if (points.size < 2) return null
    val recordedLeg = track.legs.map { it.recorded }

    // A barometer is either in the phone or it is not, so one reading anywhere in the run settles
    // the tier for all of it. It is asked this way rather than "every fix has one" because the
    // reader starts empty and is emptied again when the run stops ([BarometerReader]): a fix that
    // lands before the sensor's first event carries no pressure, and a handful of those at the start
    // of a run must not demote the whole of it to the GPS tier's ten-metre threshold.
    if (points.any { it.barometerPressureHpa != null }) {
        return ElevationProfile(
            metersAtFix = points
                .map { point -> point.barometerPressureHpa?.let { heightFromPressure(it.toDouble()) } }
                .filledFromNeighbours()
                .medianOver(BAROMETER_SMOOTHING_FIXES),
            hysteresisMeters = BAROMETER_HYSTERESIS_METERS,
            recordedLeg = recordedLeg,
        )
    }
    // Every fix must both state a height and say how wrong it might be. A fix that does not say is
    // not treated as a good one: without the bound there is no way to tell a height worth ten metres
    // of hysteresis from one worth a hundred, and a run of them would report a confident figure
    // resting on nothing.
    if (points.all { it.altitudeMeters != null && it.verticalAccuracyMeters != null }) {
        return ElevationProfile(
            metersAtFix = points.trustedGpsHeights().meanOver(GPS_SMOOTHING_FIXES),
            hysteresisMeters = GPS_HYSTERESIS_METERS,
            recordedLeg = recordedLeg,
        )
    }
    return null
}

/** How high the runner was at each fix of the track, and how far they must climb for it to count. */
internal data class ElevationProfile(
    val metersAtFix: List<Double>,
    val hysteresisMeters: Double,
    /** Whether the recording covers the stretch into each fix — `recordedLeg[i]` runs into fix i+1. */
    private val recordedLeg: List<Boolean>,
) {
    /**
     * The metres climbed between two fixes of the track, [fromIndex] to [toIndex] inclusive.
     *
     * Only what is climbed above the lowest point reached since the last climb was banked, and only
     * once it has been climbed continuously past the threshold. Then the low point is re-armed at
     * the new height, so a long hill goes on being banked as it is climbed rather than only at the
     * top.
     *
     * Nothing climbed across a break in the recording is banked: the height either side of a pause
     * or a lost signal is not a slope the runner ran up — they may have been driven up it — so each
     * unbroken stretch is accumulated from its own low point.
     */
    fun gainMetersBetween(fromIndex: Int, toIndex: Int): Double {
        var gain = 0.0
        var trough = metersAtFix[fromIndex]
        for (i in fromIndex + 1..toIndex) {
            val height = metersAtFix[i]
            if (!recordedLeg[i - 1]) {
                trough = height
                continue
            }
            if (height < trough) trough = height
            if (height - trough >= hysteresisMeters) {
                gain += height - trough
                trough = height
            }
        }
        return gain
    }
}

/**
 * Height above the standard atmosphere's sea level, in metres, for a pressure in hPa — the formula
 * `SensorManager.getAltitude` uses, written out here so the module stays free of Android and a
 * scripted run in a unit test is the whole test.
 */
private fun heightFromPressure(pressureHpa: Double): Double =
    44_330.0 * (1.0 - (pressureHpa / STANDARD_PRESSURE_HPA).pow(1.0 / 5.255))

/** The gaps taken from the nearest height either side, so the list stays one entry per fix. */
private fun List<Double?>.filledFromNeighbours(): List<Double> {
    val filled = toMutableList()
    var lastKnown: Double? = null
    for (i in indices) {
        if (filled[i] != null) lastKnown = filled[i] else filled[i] = lastKnown
    }
    // Anything before the first reading has no height behind it to take, so it takes the one ahead.
    val firstKnown = filled.firstOrNull { it != null } ?: 0.0
    return filled.map { it ?: firstKnown }
}

/** GPS heights with the ones the fix itself disowns replaced by the last one it did not. */
private fun List<TrackPoint>.trustedGpsHeights(): List<Double> {
    var lastTrusted: Double? = null
    return map { point ->
        val height = point.altitudeMeters!!
        if (point.verticalAccuracyMeters!! <= GPS_VERTICAL_ACCURACY_LIMIT_METERS) {
            lastTrusted = height
            height
        } else {
            lastTrusted ?: height
        }
    }
}

/** A centred moving average, shortened at the ends rather than dropping fixes from either. */
private fun List<Double>.meanOver(window: Int): List<Double> =
    windowedAround(window) { it.average() }

/** A centred median — what kills a one-off spike outright rather than spreading it over its neighbours. */
private fun List<Double>.medianOver(window: Int): List<Double> =
    windowedAround(window) { neighbours -> neighbours.sorted()[neighbours.size / 2] }

private fun List<Double>.windowedAround(window: Int, of: (List<Double>) -> Double): List<Double> =
    indices.map { i ->
        val from = (i - window / 2).coerceAtLeast(0)
        val to = (i + window / 2).coerceAtMost(lastIndex)
        of(subList(from, to + 1))
    }
