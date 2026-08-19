package com.example.runningapp.analysis

import com.example.runningapp.HrProfile
import com.example.runningapp.HrZone
import com.example.runningapp.data.MeasuredTrack
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.hrZoneOf

/**
 * The Run's route, ready to be drawn on a map (#47).
 *
 * The point of drawing it is the colour: a runner looking at their own route wants to see *where*
 * they left the zone they were aiming at, which is the one question the splits table and the charts
 * cannot answer — they know when, not where.
 *
 * Pure, so the whole of what the map says about a Run can be tested without a phone. What is left
 * for the screen is putting the lines on the map, which is the one part no unit test can hold.
 */
data class TrackMap(
    /** The route cut into lines, each one a stretch the Run held a single zone over. */
    val stretches: List<TrackStretch>,
    /**
     * The Run's first and last recorded fixes — where the runner set off from and finished, marked
     * so a loop can be told from an out-and-back at a glance.
     *
     * The whole recording's ends, not the drawn lines': a Run that paused in its first seconds
     * still started where it started, and a marker sitting where the recording resumed would be a
     * claim about the Run rather than about the drawing.
     */
    val start: MapFix,
    val finish: MapFix,
    /**
     * The whole route in order, each fix with how far along the Run it sits — what [fixAt] reads.
     *
     * Every fix the recording holds, not only the ones on a drawn line: a fix either side of a
     * break is still a place the runner was, and the scrubber has to be able to point at it.
     */
    val route: List<RouteFix>,
    /**
     * Which legs of [route] the recording does not cover: `i` is in here when the ground between
     * `route[i]` and `route[i + 1]` was never witnessed — a Pause, or lost signal (#69).
     *
     * The same breaks the lines are already cut at, said again as an index rather than as an
     * absence, because a reader that is not drawing has no gap to notice. What wants it is cutting
     * a Segment out of the Run ([com.example.runningapp.segments.segmentCutOf]): a stretch a runner
     * marks either side of a break must be refused, and the drawn stretches cannot answer that —
     * they are cut at every zone change too, and a colour change is not a gap in the recording.
     */
    val brokenLegs: Set<Int>,
) {
    /**
     * Where the runner was after [distanceMeters] of the Run — how a finger on the chart becomes a
     * dot on the map (#48).
     *
     * Asked in metres because that is the chart's own x axis ([DistanceChart]), and both are counted
     * off the same walk of the same track, so the two cannot disagree about where a spike happened.
     *
     * The nearest fix the Run actually recorded, on the same rule the chart reads out by
     * ([DistanceChart.readingAt]) — the dot and the readout are one answer to one question, and they
     * must name the same second of the Run. Not a point worked out between two fixes: that would
     * slide the dot along ground the chart's own line is standing still over, and on a route drawn
     * two hundred pixels tall a second of running is a tenth of a pixel anyway.
     *
     * Null past either end of the recording. There is no ground out there to point at.
     */
    fun fixAt(distanceMeters: Double): MapFix? {
        if (route.isEmpty()) return null
        if (distanceMeters < route.first().distanceMeters) return null
        if (distanceMeters > route.last().distanceMeters) return null

        val next = firstFixAtOrPast(distanceMeters)
        val ahead = route[next]
        val behind = route.getOrNull(next - 1) ?: return ahead.fix
        // Half way between two fixes reads as the earlier one, which is what the chart does with the
        // same tie — it takes the first of the points nearest the finger.
        val nearerBehind = distanceMeters - behind.distanceMeters <= ahead.distanceMeters - distanceMeters
        return if (nearerBehind) behind.fix else ahead.fix
    }

    /**
     * The first fix at or past [distanceMeters], found by halving rather than by scanning.
     *
     * The scrubber asks this on every frame of a drag, and an hour-long Run is thousands of fixes.
     *
     * *First*, so a distance a break sits on answers with the fix the runner stopped at rather than
     * the one they resumed at — which is the point the chart reads out there, and the readout and
     * the dot must not name different halves of the Run.
     */
    private fun firstFixAtOrPast(distanceMeters: Double): Int {
        var low = 0
        var high = route.lastIndex
        while (low < high) {
            val middle = (low + high) / 2
            if (route[middle].distanceMeters < distanceMeters) low = middle + 1 else high = middle
        }
        return low
    }

    /**
     * Everything the map draws, for framing the camera on the route as a whole.
     *
     * The markers are in it as well as the lines, because they are not always on one: a Run that
     * paused in its opening seconds starts at a fix no line holds, and framing on the lines alone
     * would push its own start marker off the edge of the card.
     */
    val framedFixes: List<MapFix> get() = stretches.flatMap { it.fixes } + start + finish
}

/** One fix of the Run as the map needs it: where, and nothing else. */
data class MapFix(val latitude: Double, val longitude: Double)

/** One fix of the Run and how far into it the runner had run to reach it. */
data class RouteFix(val distanceMeters: Double, val fix: MapFix)

/**
 * A stretch of the route drawn as one line, in one colour.
 *
 * A null [zone] is a stretch the Run recorded no heart rate over — a Strap that dropped out, a Run
 * that never had one, or a Run from before the app kept a track. Drawn, because the runner still
 * went that way; drawn in the app's own amber rather than in a zone's colour, because saying which
 * zone it was would be an invention.
 */
data class TrackStretch(val fixes: List<MapFix>, val zone: HrZone?)

/**
 * The Run's track cut into stretches of one zone, or null where there is no route to draw.
 *
 * Null covers a treadmill Run, a Run whose track has not loaded yet, a Run that recorded fewer than
 * two fixes, and a Run whose every leg spans a break — the page then shows no map at all, which is
 * the honest answer to "where did this Run go?" when the recording does not say.
 *
 * Three rules, each of them a place the drawing could claim more than the recording holds:
 *
 * - **A break is a break in the line.** A leg across a pause or a lost signal recorded neither where
 *   the runner went nor how long they took ([com.example.runningapp.data.TrackLeg]), so the line
 *   stops and starts again rather than running straight across ground nothing witnessed. The same
 *   cut the chart's traces are made at (#46), so the two agree about the shape of the Run.
 * - **A leg is coloured by the beats measured over it**, not by the nearest single reading — the
 *   same rule the combined chart reads a fix's heart rate by, so a sparsely recorded track folds its
 *   beats in rather than throwing all but one of them away.
 * - **Stretches meet on the fix the zone changed at.** Both lines hold that fix, so a route drawn in
 *   five colours has no gaps between them.
 *
 * [profile] is the heart rates the Run's history is banded against, which is what everything else on
 * the page is read under; null where they are not known, and then the route is drawn in one colour
 * rather than in guessed ones.
 */
internal fun trackMapOf(
    measured: MeasuredTrack,
    bpmByWallSecond: Map<Long, Int>,
    profile: HrProfile?,
): TrackMap? {
    val points = measured.points
    val legs = measured.legs
    if (legs.isEmpty()) return null

    val zoneAtFix = zoneAtEachFix(points, stretchOfEachFix(legs), bpmByWallSecond, profile)

    val stretches = mutableListOf<TrackStretch>()
    var fixes = mutableListOf<MapFix>()
    var zone: HrZone? = null
    fun close() {
        // A stretch of one fix is not a line. It happens where a single fix sits between two breaks
        // — nothing was recorded either side of it, so there is no ground of its own to draw.
        if (fixes.size >= 2) stretches += TrackStretch(fixes, zone)
        fixes = mutableListOf()
    }
    // Drawn a break at a time ([MeasuredTrack.unbrokenLegs]), and cut again inside each wherever the
    // zone changes.
    measured.unbrokenLegs.forEach { unbroken ->
        for (i in unbroken) {
            // The leg's colour is the zone at the fix that *ends* it: that reading was measured over
            // this stretch of ground, and the one before it belongs to the leg before.
            if (fixes.isEmpty() || zoneAtFix[i + 1] != zone) {
                close()
                zone = zoneAtFix[i + 1]
                fixes += points[i].asMapFix()
            }
            fixes += points[i + 1].asMapFix()
        }
        close()
    }

    if (stretches.isEmpty()) return null
    val distanceAtFix = distanceAtEachFix(legs)
    return TrackMap(
        stretches = stretches,
        start = points.first().asMapFix(),
        finish = points.last().asMapFix(),
        route = points.mapIndexed { i, point -> RouteFix(distanceAtFix[i], point.asMapFix()) },
        brokenLegs = legs.indices.filterNot { legs[it].recorded }.toSet(),
    )
}

/** The zone the heart rates measured over the ground since the previous fix average to, at each fix. */
private fun zoneAtEachFix(
    points: List<TrackPoint>,
    stretchOfFix: IntArray,
    bpmByWallSecond: Map<Long, Int>,
    profile: HrProfile?,
): List<HrZone?> {
    if (profile == null) return points.map { null }
    return bpmAtEachFix(points, stretchOfFix, bpmByWallSecond).map { it?.let { bpm -> hrZoneOf(bpm, profile) } }
}

private fun TrackPoint.asMapFix() = MapFix(latitude = latitude, longitude = longitude)
