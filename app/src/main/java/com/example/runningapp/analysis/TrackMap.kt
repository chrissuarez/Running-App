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
) {
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
    legs.forEachIndexed { i, leg ->
        if (!leg.recorded) {
            close()
            return@forEachIndexed
        }
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

    if (stretches.isEmpty()) return null
    return TrackMap(
        stretches = stretches,
        start = points.first().asMapFix(),
        finish = points.last().asMapFix(),
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
