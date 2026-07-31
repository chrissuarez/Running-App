package com.example.runningapp.analysis

import com.example.runningapp.data.HrSample
import com.example.runningapp.data.MeasuredTrack
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.measureTrack
import com.example.runningapp.run.RunMode
import kotlin.math.roundToInt

/** The distance a split is cut at. A kilometre, the unit every pace in this app is quoted in. */
const val SPLIT_METERS = 1_000.0

/**
 * How near a whole kilometre the run's final stretch may land and still be read as one rather than
 * as a distance of its own — a metre, applied at both ends of the remainder.
 *
 * This is the width of the dust, not a judgement about running. Distances are measured a leg at a
 * time and each leg is rounded to a float on the way out of
 * [com.example.runningapp.recording.geodesicDistanceMeters], so a run laid out to finish on exactly
 * three kilometres measures a fraction of a millimetre under. Without a tolerance that run would
 * show two whole splits and a "partial" of 999.99976 m, which is a bug wearing a number.
 *
 * A metre is far above that dust and far below anything a runner would recognise as a distance, so
 * the only remainders it swallows are ones that were never really there.
 */
private const val SPLIT_BOUNDARY_TOLERANCE_METERS = 1.0

/**
 * One kilometre of a run, as the splits table shows it (#45).
 *
 * Computed on read from the stored track rather than banked when the run finishes: nothing about a
 * split is a fact about the run that the track does not already hold, and a run recorded before the
 * app had a splits table gets one anyway.
 */
data class Split(
    /** 1 for the first kilometre. The final partial split carries the next number in the sequence. */
    val number: Int,
    /** [SPLIT_METERS] for every split but the last, which holds whatever the run finished on. */
    val distanceMeters: Double,
    /**
     * Minutes per kilometre over this split's moving time, or 0.0 where there is no pace to show.
     *
     * The partial final split's is a projection by construction: it is what the runner's pace over
     * that last stretch would make of a whole kilometre, which is the only pace a part of one can be
     * quoted at.
     */
    val paceMinPerKm: Double,
    /** True for the run's leftover final stretch — a split the runner did not finish. */
    val isPartial: Boolean,
    /** The average of every heart rate recorded inside this split, or null where none was. */
    val averageBpm: Int?,
    /** Metres climbed inside this split, or null when the run recorded no height at all. */
    val elevationGainMeters: Double?,
    /** This split's pace as a fraction of the slowest split's — the width of its bar. */
    val relativePace: Double,
)

/**
 * A finished run cut into its kilometres, and the metres it climbed over them (#45).
 *
 * No splits and no climb for a treadmill run or for any run with no usable track: there is no ground
 * to cut, and the distance a treadmill reports was never measured against a route. Empty, rather
 * than a single split of the whole run, so the page can simply leave the table out.
 *
 * Three decisions worth stating, because each one is a place the table could flatter the run:
 *
 * - **Pace is over moving time**, the same clock the run's own average pace is quoted against
 *   (#163). Quoting splits against the wall clock instead would have every row disagree with the
 *   summary at the top of the same page.
 * - **A split's ground is only ground the recording witnessed.** A leg spanning a pause or a gap in
 *   the recording carries neither distance nor time here ([com.example.runningapp.data.TrackLeg]).
 *   Counting its distance and not its seconds — the seconds are already rest — would read a
 *   two-minute loss of signal as an 800-metre sprint, and put a fictitious best split on the page.
 * - **A kilometre boundary is allowed to fall inside a leg**, and the leg is divided at it in
 *   proportion. Fixes arrive a second or so apart, so this rarely moves a pace by more than a
 *   moment — but a run backfilled from sparse breadcrumbs can have legs hundreds of metres long, and
 *   rounding each boundary out to the next fix would push whole splits out of shape.
 */
internal fun groundOf(run: RunnerSession, samples: List<HrSample>, track: List<TrackPoint>): RunGround {
    val nothing = RunGround(splits = emptyList(), elevationGainMeters = null)
    if (RunMode.ofSettingValue(run.runMode) == RunMode.TREADMILL) return nothing
    val measured = measureTrack(track)
    if (measured.legs.isEmpty()) return nothing
    val elevation = elevationOf(measured)
    return RunGround(
        splits = measured.splitAtKilometres(run, samples, elevation).scaledToTheSlowest(),
        // The whole run in one pass rather than the sum of the splits' gains, and not meant to match
        // it: each split banks its climbs against its own low point, so a hill straddling a
        // kilometre marker is one climb here and two there. The run's own figure is the one the
        // summary quotes, because it is the one asked about the whole run.
        elevationGainMeters = elevation?.gainMetersBetween(0, measured.points.lastIndex),
    )
}

/**
 * Everything the ground under a run says about it: its kilometres, and the metres it climbed.
 *
 * The two travel together because they come from one walk of the track, which is the expensive part
 * of the whole page — a geodesic distance per fix, on the main thread, for a run that may be an hour
 * of them. Asking for one and then the other would walk it twice.
 */
internal data class RunGround(val splits: List<Split>, val elevationGainMeters: Double?)

/** The run's legs walked once, banking a split each time a kilometre of recorded ground is behind. */
private fun MeasuredTrack.splitAtKilometres(
    run: RunnerSession,
    samples: List<HrSample>,
    elevation: ElevationProfile?,
): List<RawSplit> {
    val bpmByWallSecond = samples.byWallSecond(run)
    val splits = mutableListOf<RawSplit>()
    var startIndex = 0
    var splitMeters = 0.0
    var splitMillis = 0L

    legs.forEachIndexed { i, leg ->
        var legMeters = leg.meters
        var legMillis = leg.movingMillis
        // A while, not an if: a leg long enough to carry the run past more than one kilometre marker
        // — a sparse backfilled track, or the far side of a long gap — has to bank every one of them.
        while (legMeters > 0 && splitMeters + legMeters >= SPLIT_METERS) {
            val neededMeters = SPLIT_METERS - splitMeters
            val neededMillis = (legMillis * (neededMeters / legMeters)).toLong()
            splits += RawSplit(
                distanceMeters = SPLIT_METERS,
                movingMillis = splitMillis + neededMillis,
                isPartial = false,
                averageBpm = bpmByWallSecond.averageOver(points, startIndex, i + 1, isFirstSplit = splits.isEmpty()),
                elevationGainMeters = elevation?.gainMetersBetween(startIndex, i + 1),
            )
            legMeters -= neededMeters
            legMillis -= neededMillis
            splitMeters = 0.0
            splitMillis = 0L
            // The next split starts at the fix the boundary was crossed on. The part of this leg
            // that spilled past the marker keeps its distance and its seconds, but its heart rates
            // and its climb belong to the split the fix was recorded in — they cannot be halved.
            startIndex = i + 1
        }
        splitMeters += legMeters
        splitMillis += legMillis
    }

    // What the run finished on: a whole kilometre it landed a hair short of, a stretch of its own,
    // or nothing worth a row.
    val finishedOnTheKilometre = splitMeters >= SPLIT_METERS - SPLIT_BOUNDARY_TOLERANCE_METERS
    if (finishedOnTheKilometre || splitMeters > SPLIT_BOUNDARY_TOLERANCE_METERS) {
        splits += RawSplit(
            distanceMeters = if (finishedOnTheKilometre) SPLIT_METERS else splitMeters,
            movingMillis = splitMillis,
            isPartial = !finishedOnTheKilometre,
            averageBpm = bpmByWallSecond.averageOver(points, startIndex, points.lastIndex, isFirstSplit = splits.isEmpty()),
            elevationGainMeters = elevation?.gainMetersBetween(startIndex, points.lastIndex),
        )
    }
    return splits
}

/** A split with everything measured, waiting only to be told how it compares to the others. */
private data class RawSplit(
    val distanceMeters: Double,
    val movingMillis: Long,
    val isPartial: Boolean,
    val averageBpm: Int?,
    val elevationGainMeters: Double?,
) {
    /** Minutes per kilometre, or 0.0 when this split has no moving seconds to have run them in. */
    val paceMinPerKm: Double
        get() = if (movingMillis <= 0 || distanceMeters <= 0) {
            0.0
        } else {
            (movingMillis / 60_000.0) / (distanceMeters / SPLIT_METERS)
        }
}

/**
 * Every split measured against the slowest of them, which is the bar the runner reads the shape of
 * the run off. The partial final split is in the running for slowest like any other: it is a real
 * pace over real ground, and hiding it from the scale would draw it against a kilometre it never ran.
 *
 * A run with no pace anywhere in it — every split stood still — gets no bars rather than full ones.
 */
private fun List<RawSplit>.scaledToTheSlowest(): List<Split> {
    val slowest = maxOfOrNull { it.paceMinPerKm } ?: 0.0
    return mapIndexed { index, split ->
        Split(
            number = index + 1,
            distanceMeters = split.distanceMeters,
            paceMinPerKm = split.paceMinPerKm,
            isPartial = split.isPartial,
            averageBpm = split.averageBpm,
            elevationGainMeters = split.elevationGainMeters,
            relativePace = if (slowest > 0.0) split.paceMinPerKm / slowest else 0.0,
        )
    }
}

/**
 * The run's heart rates on the wall clock, the only axis they share with the track.
 *
 * A sample's `elapsedSeconds` counts *running* seconds, so it stands still through a pause while the
 * track's timestamps do not; rows written before v16 have no stamp of their own and elapsed seconds
 * stand in, landing late by the length of any pause before them. That is the same bounded compromise
 * the GPX export makes ([com.example.runningapp.export.RunGpxTrack]) and for the same reason: it
 * fades out with the old rows, and a per-split heart rate carrying it is worth more than none.
 *
 * The raw reading, not the smoothed one — the smoothed number is a coaching aid, and averaging an
 * average would only flatten the split into something it wasn't.
 */
private fun List<HrSample>.byWallSecond(run: RunnerSession): Map<Long, Int> =
    filter { it.rawBpm > 0 }.associate { sample ->
        val atMillis = sample.timestampMillis ?: (run.startTime + sample.elapsedSeconds * 1000)
        atMillis / 1000 to sample.rawBpm
    }

/**
 * The average heart rate recorded between two fixes of the track, or null where none was.
 *
 * The second the split *starts* on belongs to the split before it — that fix ended the previous
 * kilometre — so only the first split of the run counts its own opening second. Without that, the
 * one beat recorded on each kilometre marker would be averaged into both of its neighbours.
 */
private fun Map<Long, Int>.averageOver(
    points: List<TrackPoint>,
    fromIndex: Int,
    toIndex: Int,
    isFirstSplit: Boolean,
): Int? {
    if (isEmpty()) return null
    val fromSecond = points[fromIndex].timestampMillis / 1000 + if (isFirstSplit) 0 else 1
    val toSecond = points[toIndex].timestampMillis / 1000
    val readings = (fromSecond..toSecond).mapNotNull { this[it] }
    return if (readings.isEmpty()) null else readings.average().roundToInt()
}
