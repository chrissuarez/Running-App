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
 * How long a stretch of the run each tier averages a height over before judging it a climb.
 *
 * In time rather than in fixes, because it is the noise of the instrument that sets these and noise
 * arrives at a rate — a track sampled twice a second and one sampled every five would otherwise mean
 * quite different things by the same number.
 *
 * **Thirty seconds for the barometer is measured, not assumed** (#45). The sensor's *signal* is
 * excellent: over three of Chris's runs the height it reports at each kilometre marker agrees with
 * Strava's to within one to three metres. But between one fix and the next it jitters far more than
 * a barometer's quarter-metre precision suggests — a median of 0.87 m on those runs, 5.6 m at the
 * 95th percentile, 26 m at worst. That is well above [BAROMETER_HYSTERESIS_METERS], so a narrow
 * window banks jitter as climb all run long: at the three-second window this code first shipped
 * with, a 4.5 km run that truly climbs about 35 m reported 376 m.
 *
 * Widening the window makes the figure converge on the truth, and it has settled by thirty seconds:
 * across those three runs, three seconds gave 263/376/434 m, fifteen gave 34/61/49, thirty gave
 * 21/44/21, and sixty — half again as much smoothing — moved it only to 14/38/19. Thirty is where
 * the noise is gone and the ground has not yet started to flatten.
 *
 * Raising the threshold instead does not work, and was tried: the jitter is broad rather than spiky,
 * so a five-metre threshold on a three-second window still reported 84/92/108 m. Only the window
 * touches it.
 *
 * Five seconds for GPS is the five-fix width the research found to reproduce what Strava and Garmin
 * Connect make of the same track, restated at the one-fix-per-second those tracks are recorded at.
 */
private const val BAROMETER_SMOOTHING_MILLIS = 30_000L
private const val GPS_SMOOTHING_MILLIS = 5_000L

/**
 * How wrong a fix may say its own height is before it is passed over. The research settles that
 * there must be a cutoff without naming one; twenty metres is where a fix stops carrying information
 * a five-fix average could rescue, given that a fix states this as a one-sigma bound and a third of
 * them are worse than they claim. A trusted height stands in, rather than the fix being dropped, so
 * the heights stay one-to-one with the track's points; a run where no fix at all stands behind its
 * own height reports no elevation rather than a figure resting on nothing.
 */
private const val GPS_VERTICAL_ACCURACY_LIMIT_METERS = 20f

/**
 * How high the runner was at each fix of [track], and how far they must climb for it to count —
 * or null when the run recorded nothing to derive a height from (#20, #45).
 *
 * Two tiers, which is what both Garmin and Strava do and for the same reason: a phone barometer
 * tracks a *change* in height far more closely than a GPS fix, which is out by eight to fifteen
 * metres vertically — and gain is a sum of positive differences, so every metre of noise adds.
 * Summed raw, a flat route reports hundreds of metres of climbing.
 *
 * Neither tier is trusted fix by fix. The barometer's advantage is in where it says the runner is
 * over half a minute, not in what it reports in any one second — see [BAROMETER_SMOOTHING_MILLIS],
 * which is the whole difference between this reading a run right and reading it ten times too high.
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
    val stampedAt = points.map { it.timestampMillis }
    if (points.any { it.barometerPressureHpa != null }) {
        return ElevationProfile(
            metersAtFix = points
                .map { point -> point.barometerPressureHpa?.let { heightFromPressure(it.toDouble()) } }
                .filledFromNeighbours()
                .medianOver(BAROMETER_SMOOTHING_MILLIS, stampedAt),
            hysteresisMeters = BAROMETER_HYSTERESIS_METERS,
            recordedLeg = recordedLeg,
        )
    }
    // A fix must both state a height and say how wrong it might be to be believed: without the bound
    // there is no way to tell a height worth ten metres of hysteresis from one worth a hundred. But a
    // fix that does not say only loses its own vote — it stands in the run the same way an inaccurate
    // one does, taken from its trusted neighbours. A phone drops either field on the odd fix, and one
    // such fix must not cost the whole run its elevation.
    val trusted = points.trustedGpsHeights() ?: return null
    return ElevationProfile(
        metersAtFix = trusted.meanOver(GPS_SMOOTHING_MILLIS, stampedAt),
        hysteresisMeters = GPS_HYSTERESIS_METERS,
        recordedLeg = recordedLeg,
    )
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

/**
 * GPS heights with the ones the run does not stand behind — no height, no bound on it, or a bound
 * past [GPS_VERTICAL_ACCURACY_LIMIT_METERS] — taken from the nearest fix it does stand behind. Null
 * when no fix in the run does, so there is no height to stand in and nothing to report.
 *
 * The nearest either side, rather than only the last one behind: a run's opening fixes are the
 * likeliest to be disowned, the sky not yet fully acquired, and there is nothing behind them to take.
 * Left as they were they would report a height tens of metres out from the run that follows, and the
 * step back up to the truth would be banked as a climb the runner never made.
 */
private fun List<TrackPoint>.trustedGpsHeights(): List<Double>? {
    val trusted = map { point ->
        val within = point.verticalAccuracyMeters?.let { it <= GPS_VERTICAL_ACCURACY_LIMIT_METERS } == true
        point.altitudeMeters?.takeIf { within }
    }
    if (trusted.all { it == null }) return null
    return trusted.filledFromNeighbours()
}

/** A centred moving average, shortened at the ends rather than dropping fixes from either. */
private fun List<Double>.meanOver(windowMillis: Long, stampedAt: List<Long>): List<Double> =
    windowedAround(windowMillis, stampedAt) { it.average() }

/** A centred median — what kills a one-off spike outright rather than spreading it over its neighbours. */
private fun List<Double>.medianOver(windowMillis: Long, stampedAt: List<Long>): List<Double> =
    windowedAround(windowMillis, stampedAt) { neighbours -> neighbours.sorted()[neighbours.size / 2] }

/**
 * Each height folded together with every height recorded within half of [windowMillis] either side
 * of it, so the window is a stretch of the run rather than a count of fixes.
 *
 * The ends are shortened rather than dropped: a run's first and last fixes keep a height, taken from
 * the half-window they do have. The fixes are in time order ([measureTrack] sorts them), so the
 * window's edges only ever move forwards and the whole walk is linear in the number of fixes.
 *
 * A break in the recording is not treated specially here. It does not need to be — the fixes either
 * side of a gap are far enough apart in time to fall outside each other's window, so the heights
 * never mix; and where a pause is short enough that they do, they are moments apart and mixing them
 * is right. What must not join across a break is the *climb*, and that is
 * [ElevationProfile.gainMetersBetween]'s business.
 */
private fun List<Double>.windowedAround(
    windowMillis: Long,
    stampedAt: List<Long>,
    of: (List<Double>) -> Double,
): List<Double> {
    val half = windowMillis / 2
    var from = 0
    var to = 0
    return indices.map { i ->
        while (stampedAt[i] - stampedAt[from] > half) from++
        while (to < lastIndex && stampedAt[to + 1] - stampedAt[i] <= half) to++
        of(subList(from, to + 1))
    }
}
