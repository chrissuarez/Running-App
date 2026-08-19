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

    /** Both marks on one fix, or a Run with no route: there is no ground between them. */
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

    return SegmentCut.Cut(
        fixes = route.subList(from, to + 1).map { it.fix },
        distanceMeters = route[to].distanceMeters - route[from].distanceMeters,
    )
}

/**
 * Where the two marks sit when the screen opens: the longest stretch of the Run the recording covers
 * without a break, as indices into [TrackMap.route].
 *
 * The longest *unbroken* stretch rather than the whole Run, so the screen opens on something that
 * can actually be saved. A Run that paused halfway would otherwise greet the runner with a refusal
 * before they had touched anything, which reads as the feature being broken rather than as a rule
 * about their own recording.
 *
 * Null where the Run holds no unbroken stretch at all — nothing there could ever become a Segment,
 * and the screen says so instead of opening with handles that do nothing.
 */
fun defaultMarksFor(trackMap: TrackMap): IntRange? {
    val route = trackMap.route
    if (route.size < 2) return null

    var best: IntRange? = null
    var from = 0
    for (leg in 0 until route.lastIndex) {
        if (leg in trackMap.brokenLegs) {
            if (leg > from) best = longerOf(best, from..leg)
            from = leg + 1
        }
    }
    if (route.lastIndex > from) best = longerOf(best, from..route.lastIndex)
    return best
}

private fun longerOf(current: IntRange?, candidate: IntRange): IntRange =
    if (current == null || candidate.last - candidate.first > current.last - current.first) {
        candidate
    } else {
        current
    }

/**
 * The Run's route as lines that may be drawn — one per stretch the recording covers without a break.
 *
 * The same cut [defaultMarksFor] and [segmentCutOf] make, handed to the map so the Run drawn behind
 * the runner's choice does not run across ground nothing witnessed either.
 */
fun unbrokenStretchesOf(trackMap: TrackMap): List<List<MapFix>> {
    val route = trackMap.route
    if (route.size < 2) return emptyList()

    val stretches = mutableListOf<List<MapFix>>()
    var from = 0
    for (leg in 0 until route.lastIndex) {
        if (leg in trackMap.brokenLegs) {
            if (leg > from) stretches += route.subList(from, leg + 1).map { it.fix }
            from = leg + 1
        }
    }
    if (route.lastIndex > from) stretches += route.subList(from, route.size).map { it.fix }
    return stretches
}
