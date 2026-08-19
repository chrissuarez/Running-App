package com.example.runningapp.segments

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.analysis.TrackMap

/**
 * What the runner's two marks on a Run's map cut out of it, or why they cut nothing (#69).
 *
 * The whole of the decision, in one pure place, because a Segment's geometry is a *claim about
 * ground the runner covered* and is kept forever. Everything downstream — the row that is written,
 * the line the Segments screen draws, and one day the timing that reads it — trusts that claim
 * without re-checking it, so it is checked here once and refused here where it cannot be made.
 */
sealed interface SegmentCut {

    /** A stretch of the recording, ready to be named and kept. */
    data class Cut(
        /** The Run's own fixes between the two marks, in order, both marks included. */
        val fixes: List<MapFix>,
        /**
         * How far the stretch goes, as the Run itself counted it.
         *
         * The Run's own running total rather than a fresh walk of these fixes, so a Segment's
         * distance and the distance shown on the Run it was cut from can never disagree by a metre
         * of rounding — it is the same measurement, read at two points.
         */
        val distanceMeters: Double,
    ) : SegmentCut

    /**
     * There is no ground between the two marks: both on one fix, a Run with no route, or a stretch
     * the Run itself counted as nothing.
     *
     * The last of those is a runner who stood still. A leg between two fixes stamped the same
     * moment carries no metres by construction ([com.example.runningapp.data.measureTrack]), so a
     * mark either side of only those is two marks with a gap in the index and none on the ground.
     * Kept, it would be a place with no length, and every reading ever taken against it would be a
     * reading of nothing.
     */
    data object TooShort : SegmentCut

    /**
     * The marks sit either side of a stretch the recording does not cover — a Pause, or lost signal.
     *
     * Refused rather than drawn across. A Segment is a piece of ground the runner says they ran, and
     * the straight line over a break is ground nothing witnessed: kept, it would be a Segment that
     * claims a route the Run never recorded, and every reading taken against it afterwards would
     * inherit the invention. The same rule the route map draws by
     * ([com.example.runningapp.analysis.trackMapOf]), applied to keeping rather than to drawing.
     */
    data object SpansABreak : SegmentCut
}

/**
 * Cuts [trackMap] between two marks, given as indices into its [TrackMap.route].
 *
 * The marks may arrive either way round — the runner drags two handles and nothing stops the second
 * passing the first — and either may sit off the end of the recording, so both are put in order and
 * pulled back onto the route before anything is read.
 */
fun segmentCutOf(trackMap: TrackMap, markA: Int, markB: Int): SegmentCut {
    val route = trackMap.route
    if (route.size < 2) return SegmentCut.TooShort

    val from = minOf(markA, markB).coerceIn(route.indices)
    val to = maxOf(markA, markB).coerceIn(route.indices)
    if (from == to) return SegmentCut.TooShort
    if ((from until to).any { it in trackMap.brokenLegs }) return SegmentCut.SpansABreak

    // Measured on the Run's own total rather than on the index: the two are not the same question.
    // Indices apart say the recording wrote something down between the marks; metres apart say the
    // runner went somewhere. A stretch that answers the first and not the second is a Segment with
    // no length, which is [TooShort] whatever the handles look like.
    val distanceMeters = route[to].distanceMeters - route[from].distanceMeters
    if (distanceMeters <= 0.0) return SegmentCut.TooShort

    return SegmentCut.Cut(
        fixes = route.subList(from, to + 1).map { it.fix },
        distanceMeters = distanceMeters,
    )
}

/**
 * The stretches of the Run the recording covers without a break, as ranges of index into
 * [TrackMap.route].
 *
 * The one walk of the breaks, because everything a Segment is cut with has to agree about where
 * they are: where the handles open ([defaultMarksFor]), what may be drawn behind them
 * ([unbrokenStretchesOf]), and — through [segmentCutOf]'s own read of [TrackMap.brokenLegs] —
 * whether the stretch between them may be kept at all.
 *
 * A range of one fix is left out: it is a place the runner was, but no ground to draw or to cut.
 */
fun unbrokenRangesOf(trackMap: TrackMap): List<IntRange> {
    val route = trackMap.route
    if (route.size < 2) return emptyList()

    val ranges = mutableListOf<IntRange>()
    var from = 0
    for (leg in 0 until route.lastIndex) {
        if (leg in trackMap.brokenLegs) {
            if (leg > from) ranges += from..leg
            from = leg + 1
        }
    }
    if (route.lastIndex > from) ranges += from..route.lastIndex
    return ranges
}

/**
 * Where the two marks sit when the screen opens: the longest stretch of the Run the recording covers
 * without a break.
 *
 * The longest *unbroken* stretch rather than the whole Run, so the screen opens on something that
 * can actually be saved. A Run that paused halfway would otherwise greet the runner with a refusal
 * before they had touched anything, which reads as the feature being broken rather than as a rule
 * about their own recording.
 *
 * Null where the Run holds no unbroken stretch at all — nothing there could ever become a Segment,
 * and the screen says so instead of opening with handles that do nothing.
 */
fun defaultMarksFor(trackMap: TrackMap): IntRange? =
    unbrokenRangesOf(trackMap)
        // Only stretches that would actually be kept. Asked of [segmentCutOf] rather than checked
        // again here, so there is one answer to what may be cut and the screen cannot open on
        // handles that are already as far apart as they go and still refused — a runner told to
        // move a mark they cannot move reads the feature as broken rather than their standing
        // still as the reason.
        .filter { segmentCutOf(trackMap, it.first, it.last) is SegmentCut.Cut }
        .maxByOrNull { it.last - it.first }

/**
 * The Run's route as lines that may be drawn — one per stretch the recording covers without a break.
 *
 * The same cut [defaultMarksFor] and [segmentCutOf] make, handed to the map so the Run drawn behind
 * the runner's choice does not run across ground nothing witnessed either.
 */
fun unbrokenStretchesOf(trackMap: TrackMap): List<List<MapFix>> =
    unbrokenRangesOf(trackMap).map { range ->
        trackMap.route.subList(range.first, range.last + 1).map { it.fix }
    }
