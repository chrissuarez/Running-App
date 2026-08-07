package com.example.runningapp.analysis

import com.example.runningapp.data.MeasuredTrack
import com.example.runningapp.data.TrackLeg
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * How long a stretch of ground each point's pace is measured over — two hundred metres, a hundred
 * either side of it.
 *
 * Pace has to be smoothed because it is a *rate*, and a rate read off two consecutive fixes divides
 * by the second between them: a fix landing thirty metres off the route — well within what a phone
 * does under trees — reads as a thirty-metre second, which is a 0:33 /km sprint followed by a
 * standstill. Neither happened. Unsmoothed, a run's pace line is those spikes and almost nothing
 * else.
 *
 * In distance rather than in time, because pace is what the runner covered *ground* at, and a window
 * of seconds would cover a hundred metres running and twenty walking — so the walk would be smoothed
 * five times as hard as the run, exactly where the line is most worth reading.
 *
 * **Two hundred metres is measured against Chris's own recorded runs, not assumed** (#46). Replaying
 * five of his GPX exports through this module, leg by leg the raw pace runs from 2:31 /km to 63:00
 * /km on runs he actually held between about 5:30 and 12:00 — the noise is larger than the signal by
 * an order of magnitude, and three or four legs of every run read faster than 3:00 /km.
 *
 * Three windows, on those same runs:
 *
 * - **100 m** still reports a 4:26 /km fastest point on a run/walk workout, and the line moves up to
 *   0.56 min/km between one drawn point and the next — visibly ragged.
 * - **200 m** brings the fastest point back to a real 5:00–5:50 /km and the step between drawn
 *   points down to 0.28 min/km at worst, 0.02 typically. The line reads as a line.
 * - **400 m** is smoother still (0.19 max step) but starts flattening the run: on a run/walk
 *   workout the spread between the running and walking stretches closes from 5:01–12:30 to
 *   6:33–12:14, and Chris's walk breaks stop being visible as walk breaks.
 *
 * So 200 m is where the noise has gone and the run has not gone with it. It does cost some contrast
 * — a one-minute walk break covers only about ninety metres, so a window centred inside one still
 * sees running either side of it, and the line dips towards the walk rather than reaching it. The
 * splits table (#45) is where a kilometre's pace is read exactly; this line is for the shape.
 */
private const val PACE_SMOOTHING_METERS = 200.0

/**
 * The fastest and slowest the pace scale is ever allowed to reach, in minutes per kilometre.
 *
 * A scale is set by the extremes of what it has to show, so one impossible point would otherwise
 * squash the whole run into a sliver. Two minutes a kilometre is faster than the mile record; twenty
 * is slower than a stroll. Anything past either is an artefact of the recording rather than a pace,
 * and rides the edge of the frame instead of setting it.
 */
internal const val FASTEST_PLAUSIBLE_PACE_MIN_PER_KM = 2.0
internal const val SLOWEST_PLAUSIBLE_PACE_MIN_PER_KM = 20.0

/** Air above and below the run's own pace range, so the line never touches the frame. */
private const val PACE_HEADROOM_MIN_PER_KM = 0.5

/**
 * The least height the silhouette's scale ever spans.
 *
 * Without it, a run over flat ground has the metre of instrument noise it recorded stretched across
 * the full height of the chart, and reads as a mountain stage. Twenty metres is about the smallest
 * rise a runner would notice in their legs, so a run whose ground stays inside it is drawn as what
 * it was: flat.
 */
internal const val MINIMUM_ELEVATION_BAND_METERS = 20.0

/**
 * The Run drawn against the ground it covered: pace and heart rate over an elevation silhouette,
 * with the kilometres along the bottom (#46).
 *
 * This is the mode an outdoor Run is worth reading in, because it is the one that answers *why*.
 * Heart rate over the clock ([RunChart]) shows a spike; the same spike over distance sits on top of
 * the hill that caused it. A treadmill Run has no ground to draw against and keeps [RunChart].
 *
 * The three series share one x axis and one set of breaks, because they come from one walk of the
 * track. The axis is the Run's own distance, so an Outage opens a gap in it as wide as the ground
 * the runner covered while the signal was down (#204), and a Pause — no ground covered, the recording
 * torn down — leaves the stretches either side of it meeting at the same metre. Either way they are
 * separate traces: the height on the far side of a Break is not a slope the runner ran up, and
 * drawing one line across would say they did.
 */
data class DistanceChart(
    /** The stretches of the Run that were recorded without a break, each drawable as one line. */
    val traces: List<DistanceTrace>,
    /** The ground the Run covered, which is the distance the rest of the page quotes. */
    val distanceMetersSpan: Double,
    val bpmFloor: Int,
    val bpmCeiling: Int,
    /** The top of the pace axis — faster is higher, the way a runner reads a good split. */
    val paceFastestMinPerKm: Double,
    val paceSlowestMinPerKm: Double,
    /** The band the silhouette is drawn in, or null when the Run recorded no height to draw. */
    val elevationBand: ElevationBand?,
) {
    /**
     * Which of the three lines this Run actually recorded — what the page is allowed to name.
     *
     * An outdoor Run without a Strap is a first-class Run (#110) and draws no red line; a Run whose
     * every second was banked as rest has no pace. Naming a line that is not on the chart, in the
     * heading or in the key, sends the runner looking for it.
     */
    val hasPace: Boolean get() = traces.any { trace -> trace.points.any { it.paceMinPerKm != null } }
    val hasHeartRate: Boolean get() = traces.any { trace -> trace.points.any { it.bpm != null } }
    val hasElevation: Boolean get() = elevationBand != null

    /**
     * Every point of the Run in one list, in order — worked out once because [readingAt] is asked on
     * every frame of a drag and #48 is a measured jank fix, not a hypothetical one.
     */
    private val allPoints: List<DistancePoint> by lazy(LazyThreadSafetyMode.NONE) {
        traces.flatMap { it.points }
    }

    /**
     * What the runner's finger is over: the point of the Run nearest that distance, or null past
     * either end of it.
     *
     * The nearest across the whole Run rather than within one trace, because the ground an Outage
     * spans takes up room on this axis (#204) and no trace covers it. A finger in there reads out the
     * fix either side of it that it is nearer to — the same rule, and the same answer, as the dot the
     * map puts on the route ([TrackMap.fixAt]), so the two never name different seconds of the Run.
     * The line still stops at the Break; it is the readout that goes on.
     *
     * Read by halving and comparing the two neighbours, which is [TrackMap.fixAt]'s own working —
     * not a scan for the smallest gap. A Pause leaves two points on the same metre, and a scan
     * cannot tell them apart: it would hold the readout on the fix the runner stopped at while the
     * dot had already moved to the one they resumed at, so the page would name two different halves
     * of the Run at once.
     */
    fun readingAt(distanceMeters: Double): DistancePoint? {
        val first = allPoints.firstOrNull() ?: return null
        val last = allPoints.last()
        if (distanceMeters < first.distanceMeters || distanceMeters > last.distanceMeters) return null

        val next = firstPointAtOrPast(distanceMeters)
        val ahead = allPoints[next]
        val behind = allPoints.getOrNull(next - 1) ?: return ahead
        // Half way between two points reads as the earlier one, the tie the map breaks the same way.
        val nearerBehind = distanceMeters - behind.distanceMeters <= ahead.distanceMeters - distanceMeters
        return if (nearerBehind) behind else ahead
    }

    /** The first point at or past [distanceMeters] — [TrackMap.fixAt]'s search, over the same fixes. */
    private fun firstPointAtOrPast(distanceMeters: Double): Int {
        var low = 0
        var high = allPoints.lastIndex
        while (low < high) {
            val middle = (low + high) / 2
            if (allPoints[middle].distanceMeters < distanceMeters) low = middle + 1 else high = middle
        }
        return low
    }
}

/** A stretch of the Run recorded without a break, and so drawable as one line. */
data class DistanceTrace(val points: List<DistancePoint>)

/**
 * One fix of the Run, with everything the page draws at it.
 *
 * Each of the three may be absent on its own: a Run recorded no height at all, a Strap dropped out
 * for a minute, or the runner stood still long enough for the recording to bank it as rest — and
 * ground covered over no moving seconds has no pace, rather than an infinitely fast one.
 */
data class DistancePoint(
    val distanceMeters: Double,
    val paceMinPerKm: Double?,
    /**
     * How far above the Run's own lowest point the runner was, in metres — not how high above sea
     * level, which this app does not know.
     *
     * A barometer measures a pressure, and the height that pressure works out to is a height above
     * the *standard* atmosphere's sea level rather than today's. The difference is the weather, and
     * on the run this was first read on it came to about sixty metres: the readout said the runner
     * was two metres below sea level on ground that is fifty-odd above it.
     *
     * [elevationOf] never corrects that offset, deliberately — climb is a sum of differences and the
     * offset cancels out of every one of them. So the honest number to show is the one the offset
     * cancels out of here too: how much higher than the bottom of this run.
     */
    val metersAboveLowestPoint: Double?,
    val bpm: Int?,
)

/**
 * The Run's track walked into the three series the combined chart draws, or null when there is no
 * ground to draw them against.
 *
 * Null covers a treadmill Run, a Run whose track has not loaded yet, and a Run that never left the
 * spot: an axis from zero to zero is nothing to put a finger on, and the page shows heart rate over
 * the clock instead.
 *
 * [measured] and [elevation] are handed in already worked out because [groundOf] needs both for the
 * splits, and walking a track is the expensive part of the whole page.
 */
internal fun distanceChartOf(
    measured: MeasuredTrack,
    elevation: ElevationProfile?,
    bpmByWallSecond: Map<Long, Int>,
): DistanceChart? {
    val points = measured.points
    val legs = measured.legs
    if (legs.isEmpty()) return null

    // Where along the run each fix sits — the same axis the route map measures itself along, so a
    // finger on this chart finds the right ground on that map (#48).
    val distanceAtFix = distanceAtEachFix(legs)
    val stretchOfFix = stretchOfEachFix(legs)
    val span = distanceAtFix.last()
    if (span <= 0.0) return null

    val pace = measured.smoothedPaceAtEachFix(distanceAtFix, stretchOfFix)
    val heights = elevation?.metersAtFix
    val bpm = bpmAtEachFix(points, stretchOfFix, bpmByWallSecond)

    // Heights are re-stated against the Run's own lowest point before anything is drawn or read
    // out, so no absolute height reaches the screen. See [DistancePoint.metersAboveLowestPoint].
    val lowest = heights?.minOrNull()
    val drawn = points.indices.map { i ->
        DistancePoint(
            distanceMeters = distanceAtFix[i],
            paceMinPerKm = pace[i],
            metersAboveLowestPoint = if (heights == null || lowest == null) null else heights[i] - lowest,
            bpm = bpm[i],
        )
    }

    val beats = drawn.mapNotNull { it.bpm }
    val paces = drawn.mapNotNull { it.paceMinPerKm }
        .map { it.coerceIn(FASTEST_PLAUSIBLE_PACE_MIN_PER_KM, SLOWEST_PLAUSIBLE_PACE_MIN_PER_KM) }
    val silhouette = drawn.mapNotNull { it.metersAboveLowestPoint }

    return DistanceChart(
        traces = drawn.cutAtBreaks(stretchOfFix),
        distanceMetersSpan = span,
        bpmFloor = bpmFloorFor(beats),
        bpmCeiling = bpmCeilingFor(beats),
        // Faster is a smaller number, so the top of the axis is the smallest pace the run held.
        paceFastestMinPerKm = paceScaleEdge(paces.minOrNull(), -PACE_HEADROOM_MIN_PER_KM, ::floor),
        paceSlowestMinPerKm = paceScaleEdge(paces.maxOrNull(), PACE_HEADROOM_MIN_PER_KM, ::ceil),
        elevationBand = elevationBandFor(silhouette),
    )
}

/** The points cut into the stretches the recording covered without a break. */
private fun List<DistancePoint>.cutAtBreaks(stretchOfFix: IntArray): List<DistanceTrace> {
    val traces = mutableListOf(mutableListOf(first()))
    for (i in 1..lastIndex) {
        if (stretchOfFix[i] != stretchOfFix[i - 1]) traces += mutableListOf(this[i]) else traces.last() += this[i]
    }
    return traces.map { DistanceTrace(it) }
}

/**
 * The pace at each fix, measured over [PACE_SMOOTHING_METERS] of the run centred on it.
 *
 * Over moving time and recorded ground, the same two quantities the splits table divides (#45), so a
 * point of the line and the split it sits in cannot disagree about the run. Null where the window
 * holds no ground or no moving seconds: a stretch banked as rest is not a fast kilometre, it is no
 * pace at all, and quoting a pace over zero seconds would put an infinity on the chart.
 *
 * A window stops at a break in the recording, for the same reason the elevation windows do — the two
 * sides are not one stretch of ground, and pace measured across a pause would divide ground the
 * recording witnessed by seconds it did not.
 */
private fun MeasuredTrack.smoothedPaceAtEachFix(
    distanceAtFix: DoubleArray,
    stretchOfFix: IntArray,
): List<Double?> {
    val half = PACE_SMOOTHING_METERS / 2
    // A leg belongs to the stretch its first fix is in. That is exact for a recorded leg, whose two
    // fixes share a stretch, and it puts a break leg at the end of the stretch running into it —
    // where it is skipped outright below, and where the legs past it are in a stretch of their own
    // and stop the window anyway.
    var from = 0
    var to = -1
    return points.indices.map { i ->
        val lowest = distanceAtFix[i] - half
        val highest = distanceAtFix[i] + half
        if (to < i - 1) to = i - 1
        while (from < legs.size &&
            (stretchOfFix[from] < stretchOfFix[i] || distanceAtFix[from + 1] < lowest)
        ) {
            from++
        }
        while (to + 1 < legs.size &&
            stretchOfFix[to + 1] == stretchOfFix[i] &&
            distanceAtFix[to + 1] <= highest
        ) {
            to++
        }
        if (from > to) return@map null
        var meters = 0.0
        var movingMillis = 0.0
        for (j in from..to) {
            // An Outage carries ground but no seconds the recording can vouch for (#204). Divided one
            // by the other it is a sprint the runner never ran, so the line the runner reads the
            // shape of the Run off leaves it out entirely rather than folding half a tunnel into the
            // pace either side of it. The splits table is where that ground is accounted for.
            if (!legs[j].recorded) continue
            val share = legs[j].shareInside(distanceAtFix[j], distanceAtFix[j + 1], lowest, highest)
            meters += legs[j].meters * share
            movingMillis += legs[j].movingMillis * share
        }
        if (meters <= 0.0 || movingMillis <= 0.0) null else (movingMillis / 60_000.0) / (meters / 1_000.0)
    }
}

/**
 * How much of a leg lies inside the window — its overlap as a fraction of its own length.
 *
 * Legs are taken in proportion rather than whole or not at all, the same way [groundOf] divides a
 * leg that straddles a kilometre marker (#45). Two reasons, one about every run and one about the
 * awkward ones:
 *
 * - **The window's edges stay honest.** A leg half inside it contributes half its ground and half
 *   its seconds, so the window really is two hundred metres wide rather than however far the nearest
 *   fix happens to land — which is what keeps the line steady between one drawn point and the next.
 * - **It does not fall over on a long leg.** Judging a leg by whether its midpoint is inside the
 *   window leaves a leg longer than the window in no window at all, and that fix would come out with
 *   no pace. Within [com.example.runningapp.data.TRACK_BREAK_MS] a leg that long needs a runner
 *   doing ten metres a second, so no recorded run has one — but the rule should not be the thing
 *   holding that up.
 *
 * A leg with no length is inside or outside as a whole — it has no proportion to take, and it can
 * still carry seconds the runner spent going nowhere.
 */
private fun TrackLeg.shareInside(from: Double, to: Double, lowest: Double, highest: Double): Double {
    val length = to - from
    if (length <= 0.0) return if (from in lowest..highest) 1.0 else 0.0
    val overlap = minOf(to, highest) - maxOf(from, lowest)
    return (overlap / length).coerceIn(0.0, 1.0)
}

/**
 * One edge of the pace scale: the run's own extreme, given air and rounded outwards to a whole
 * minute so the axis reads in numbers a runner quotes paces in. Held inside the plausible range so a
 * fix landing in the next county cannot set it.
 *
 * A run with no pace anywhere in it still gets a scale, so the chart has a frame to draw its heart
 * rate and its ground in.
 */
private fun paceScaleEdge(pace: Double?, headroom: Double, round: (Double) -> Double): Double =
    round((pace ?: (FASTEST_PLAUSIBLE_PACE_MIN_PER_KM + SLOWEST_PLAUSIBLE_PACE_MIN_PER_KM) / 2) + headroom)
        .coerceIn(FASTEST_PLAUSIBLE_PACE_MIN_PER_KM, SLOWEST_PLAUSIBLE_PACE_MIN_PER_KM)

/**
 * The round distances to label along the bottom of the chart, chosen so a two-kilometre jog and a
 * half marathon both get a handful of ticks rather than three and forty.
 *
 * The finish always gets one, because the total is the number the runner is looking for — unless the
 * last round tick sits close enough to collide with it, in which case the round one gives way.
 *
 * "Close enough" is three quarters of a step, which is wider than it sounds it should be. A label
 * reads "4.53 km" and is most of a step wide on its own, so a 4.53 km run with a tick left at 4 km
 * printed "4.00 km4.53 km" — the two labels touching, which is how this number was arrived at rather
 * than by choosing it.
 */
fun kilometreTicks(spanMeters: Double): List<Double> {
    val step = distanceTickSteps().first { spanMeters / it <= MAX_DISTANCE_TICKS }
    val ticks = generateSequence(0.0) { it + step }.takeWhile { it < spanMeters }.toMutableList()
    if (ticks.size > 1 && spanMeters - ticks.last() < step * FINISH_LABEL_CLEARANCE) {
        ticks.removeAt(ticks.lastIndex)
    }
    ticks += spanMeters
    return ticks
}

/** How much of a step must separate the last round tick from the finish for both to be printed. */
private const val FINISH_LABEL_CLEARANCE = 0.75

/**
 * The step sizes a distance axis may be ticked at, smallest first and going up for ever: 200 m,
 * 500 m, 1 km, 2 km, 5 km, 10 km, and on.
 *
 * Unbounded rather than a fixed list, because a list runs out. Stopping at 10 km would tick a
 * hundred-kilometre run eleven times across the same width the handful was chosen to fit.
 */
private fun distanceTickSteps(): Sequence<Double> =
    generateSequence(100.0) { it * 10 }.flatMap { sequenceOf(it * 2, it * 5, it * 10) }

private const val MAX_DISTANCE_TICKS = 6

/** A distance as a runner reads it: kilometres to two places, or whole metres under one. */
fun formatDistance(meters: Double): String =
    if (meters < 1_000) "${meters.roundToInt()} m" else "%.2f km".format(meters / 1_000)

/**
 * The band the silhouette's scale runs between, or null when the Run recorded no height at all —
 * in metres above the Run's own lowest point, like everything else about height here.
 *
 * The two edges are one thing, not two: the Run either has ground to draw or it does not, and a
 * floor without a ceiling is not a state the chart can be in.
 */
data class ElevationBand(val floorMeters: Double, val ceilingMeters: Double)

/**
 * The run's own highest and lowest, opened out just far enough to reach
 * [MINIMUM_ELEVATION_BAND_METERS] and no further.
 */
private fun elevationBandFor(heights: List<Double>): ElevationBand? {
    if (heights.isEmpty()) return null
    val padding = ((MINIMUM_ELEVATION_BAND_METERS - (heights.max() - heights.min())) / 2)
        .coerceAtLeast(0.0)
    return ElevationBand(heights.min() - padding, heights.max() + padding)
}
