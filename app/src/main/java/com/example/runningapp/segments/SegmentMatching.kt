package com.example.runningapp.segments

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.MeasuredTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whether a Run covered a Segment, and how long it took over it (#70).
 *
 * The one place that decides what counts as having run a stretch of ground. Everything downstream —
 * the efforts written to the database, the PR on a Segment's page, the whole of what the runner is
 * measuring themselves against — is this answer, banked. So it is pure and it is scripted: the
 * tolerances below are boundaries, and a boundary pinned by a recorded Run is a boundary pinned by
 * whatever that Run happened to do. [SegmentMatchingTest] writes tracks in metres and says where
 * each one should land.
 *
 * The rule is a gate at either end with a corridor between them, which is the shape Strava's is and
 * the shape it has to be. Gates alone would count a Run that passed the start, went somewhere else
 * entirely and came back for the end. A corridor alone would count a Run that joined the stretch
 * half way along. Together they say what a runner means by "I ran the hill": they were at the
 * bottom, they went up it, and they came out of the top.
 */

/** One fix of a Run as the matching needs it: where, when, and whether the recording jumped to get here. */
data class SegmentTrackFix(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    /**
     * Whether the ground between the fix before this one and this one went unwitnessed — a Pause,
     * or lost signal ([com.example.runningapp.data.TrackLeg.recorded]).
     *
     * An effort may not reach across one. Not because the runner necessarily left the corridor, but
     * because the effort is a *time*: a Pause is seconds the Run itself refused to count, and an
     * Outage is seconds nothing witnessed. Timed across either, a Segment's PR would be a number
     * nobody ran, and it would sit on the page next to times that mean something.
     */
    val followsABreak: Boolean = false,
)

/** One time a Run went over a Segment, gate to gate. */
data class SegmentTraversal(
    /** When the runner crossed the start gate, worked out between the two fixes either side of it. */
    val startedAtMillis: Long,
    /** When they crossed the end gate, worked out the same way. */
    val finishedAtMillis: Long,
) {
    val elapsedMillis: Long get() = finishedAtMillis - startedAtMillis
}

/**
 * How close to the Segment's own start and end the Run has to pass for the crossing to be a
 * crossing rather than a near miss.
 *
 * Thirty metres is roughly a GPS fix's worth of doubt in a street of buildings plus the width of a
 * road. Tighter and a Run down the far pavement of the same street stops counting; looser and the
 * gate reaches across to the parallel road.
 */
const val SEGMENT_GATE_METERS: Double = 30.0

/**
 * How far off the Segment's line a Run may be and still be said to be on it.
 *
 * Narrower than the gates on purpose. The gates are about arriving somewhere; this is about staying
 * on the ground the Segment is made of, and a runner who is twenty-five metres to the side of a
 * street is on a different street.
 */
const val SEGMENT_CORRIDOR_METERS: Double = 25.0

/**
 * How long a Run may be outside the corridor before it stops being a blip and starts being a
 * different route.
 *
 * Both limits have to hold, because either alone is a hole. Time alone would let a runner leave the
 * corridor at a sprint and rejoin fifty metres further on; ground alone would let them stand off the
 * line for a minute. What a GPS blip actually is — a fix or two flung sideways by a reflection off a
 * building — is short in both.
 */
const val SEGMENT_BLIP_MILLIS: Long = 10_000L
const val SEGMENT_BLIP_METERS: Double = 40.0

/**
 * How far back down the Segment a Run may slip between two fixes without the traversal being
 * abandoned — jitter, not turning round.
 */
private const val SEGMENT_BACKTRACK_METERS: Double = 15.0

/**
 * How much further along the Segment a Run may get than the ground it actually covered since the
 * last fix.
 *
 * The Segment's line bends where the Run's fix-to-fix legs are straight, so a leg round a corner
 * gains a metre or two on the line it is being measured against. What this is really doing is
 * refusing the opposite: a Run cannot get two hundred metres further up a Segment on a leg that
 * covered five, which is what a shortcut looks like from the line's point of view.
 */
private const val SEGMENT_ADVANCE_SLACK_METERS: Double = 15.0

/** Below this, two distances along the line are the same place. */
private const val EPSILON_METERS = 1e-6

/**
 * Every time [track] went over [ground], in the order the Run made them.
 *
 * [ground] is the Segment's own line, decoded from its row; [track] is the Run's accuracy-filtered
 * fixes in time order ([segmentTrackOf]). A Run that went over the stretch three times holds three
 * efforts — the runner did run it three times, and the page is a list of efforts rather than a list
 * of Runs.
 */
fun segmentTraversalsIn(ground: List<MapFix>, track: List<SegmentTrackFix>): List<SegmentTraversal> {
    if (ground.size < 2 || track.size < 2) return emptyList()

    val frame = LocalFrame(ground.first())
    val line = ground.map { frame.project(it.latitude, it.longitude) }
    val alongAt = alongEachPointOf(line)
    val length = alongAt.last()
    if (length <= EPSILON_METERS) return emptyList()

    val fixes = track.map { frame.project(it.latitude, it.longitude) }
    val matcher = Matcher(line, alongAt, length, fixes, track)

    val traversals = mutableListOf<SegmentTraversal>()
    var from = 0
    while (from < fixes.lastIndex) {
        if (!matcher.arrivesAtTheStartGate(from)) {
            from++
            continue
        }
        val to = matcher.leavesTheEndGateAfter(from)
        if (to == null) {
            from++
            continue
        }
        matcher.traversalBetween(from, to)?.let { traversals += it }
        from = to
    }
    return traversals
}

/**
 * A finished Run's track as the matching wants it — the same measured legs everything else reads the
 * Run's shape from, so the breaks an effort may not reach across are the breaks the map draws to and
 * the splits table cuts at.
 */
fun segmentTrackOf(measured: MeasuredTrack): List<SegmentTrackFix> =
    measured.points.mapIndexed { i, point ->
        SegmentTrackFix(
            latitude = point.latitude,
            longitude = point.longitude,
            timestampMillis = point.timestampMillis,
            followsABreak = i > 0 && !measured.legs[i - 1].recorded,
        )
    }

/** One traversal being tried, and the rules it has to keep. */
private class Matcher(
    private val line: List<Point>,
    private val alongAt: DoubleArray,
    private val length: Double,
    private val fixes: List<Point>,
    private val track: List<SegmentTrackFix>,
) {

    private val start = line.first()
    private val end = line.last()

    /**
     * Whether the Run is at the start gate at this fix and has not set off up the Segment yet —
     * near the Segment's own first point, and with none of the stretch behind it.
     */
    fun arrivesAtTheStartGate(at: Int): Boolean =
        fixes[at].metersTo(start) <= SEGMENT_GATE_METERS &&
            nearestPointOnTheLine(fixes[at], alongLow = 0.0, alongHigh = length).along <= EPSILON_METERS

    /**
     * The fix the Run comes out of the end gate on, having kept every rule from [from] onwards — or
     * null, which is the answer for most of a Run and every Run that only went near.
     */
    fun leavesTheEndGateAfter(from: Int): Int? {
        // How far up the Segment the Run has got. Never allowed to jump further than the ground the
        // Run itself covered, which is what makes a shortcut unable to appear on the far side of the
        // stretch rather than something to be detected afterwards.
        var along = 0.0
        var strayedAtMillis: Long? = null
        var strayedAtAlong = 0.0

        for (at in from + 1 until fixes.size) {
            if (track[at].followsABreak) return null

            val covered = fixes[at - 1].metersTo(fixes[at])
            val nearest = nearestPointOnTheLine(
                from = fixes[at],
                alongLow = along - SEGMENT_BACKTRACK_METERS,
                alongHigh = along + covered + SEGMENT_ADVANCE_SLACK_METERS,
            )

            when {
                // Out of the far end: the effort stands or falls here.
                nearest.along >= length - EPSILON_METERS ->
                    return if (fixes[at].metersTo(end) <= SEGMENT_GATE_METERS) at else null

                // Not into it yet — still milling about at the start gate, which is allowed as long
                // as it is still the start gate they are milling about at.
                nearest.along <= EPSILON_METERS -> {
                    if (fixes[at].metersTo(start) > SEGMENT_GATE_METERS) return null
                    strayedAtMillis = null
                }

                nearest.offset > SEGMENT_CORRIDOR_METERS -> {
                    if (strayedAtMillis == null) {
                        strayedAtMillis = track[at - 1].timestampMillis
                        strayedAtAlong = along
                    }
                    if (track[at].timestampMillis - strayedAtMillis > SEGMENT_BLIP_MILLIS) return null
                    if (nearest.along - strayedAtAlong > SEGMENT_BLIP_METERS) return null
                }

                else -> strayedAtMillis = null
            }

            along = maxOf(along, nearest.along)
        }
        return null
    }

    /**
     * The effort itself: the wall clock between the two gate crossings, each worked out on the leg
     * that crosses it rather than at the fix nearest to it.
     *
     * Interpolated because a fix a second either side of a gate is several metres either side of it,
     * and on a three-hundred-metre Segment that is the difference between a PR and not. Null for a
     * crossing pair that leaves no time between them, which two fixes stamped the same moment would.
     */
    fun traversalBetween(from: Int, to: Int): SegmentTraversal? {
        val startedAt = crossingMillis(from, from + 1, start)
        val finishedAt = crossingMillis(to - 1, to, end)
        return if (finishedAt > startedAt) SegmentTraversal(startedAt, finishedAt) else null
    }

    /** When the leg from [from] to [to] passed [gate], as a fraction of the leg's own clock. */
    private fun crossingMillis(from: Int, to: Int, gate: Point): Long {
        val fromMillis = track[from].timestampMillis
        val toMillis = track[to].timestampMillis
        val crossed = fixes[from].fractionNearest(fixes[to], gate)
        return (fromMillis + crossed * (toMillis - fromMillis)).roundToLong()
    }

    /**
     * The nearest point of the Segment's line to [from], looking only between [alongLow] and
     * [alongHigh] — how far along it sits, and how far off the line [from] is.
     *
     * Restricted rather than looking at the whole line, because a Segment that doubles back on
     * itself has two pieces of line under the same patch of ground, and the runner is on the one
     * they have run to rather than on whichever is nearest.
     */
    fun nearestPointOnTheLine(from: Point, alongLow: Double, alongHigh: Double): OnTheLine {
        val low = alongLow.coerceIn(0.0, length)
        val high = alongHigh.coerceIn(low, length)
        var best = OnTheLine(along = low, offset = Double.MAX_VALUE)
        for (leg in 0 until line.lastIndex) {
            val legFrom = alongAt[leg]
            val legTo = alongAt[leg + 1]
            val legMeters = legTo - legFrom
            if (legMeters <= EPSILON_METERS) continue
            if (legTo < low || legFrom > high) continue

            val fraction = line[leg]
                .fractionNearest(line[leg + 1], from)
                .coerceIn((low - legFrom) / legMeters, (high - legFrom) / legMeters)
                .coerceIn(0.0, 1.0)
            val on = line[leg].along(line[leg + 1], fraction)
            val offset = on.metersTo(from)
            if (offset < best.offset) best = OnTheLine(along = legFrom + fraction * legMeters, offset = offset)
        }
        return best
    }
}

/** A point of the Segment's line: how far along it is, and how far the Run was off it there. */
private data class OnTheLine(val along: Double, val offset: Double)

/** How far along the line each of its points sits. */
private fun alongEachPointOf(line: List<Point>): DoubleArray {
    val along = DoubleArray(line.size)
    for (i in 1 until line.size) along[i] = along[i - 1] + line[i - 1].metersTo(line[i])
    return along
}

/** A place, in metres east and north of wherever the frame was pinned. */
private data class Point(val east: Double, val north: Double) {

    fun metersTo(other: Point): Double = hypot(other.east - east, other.north - north)

    /** How far along this-to-[other] the point nearest [target] sits, as a fraction of the leg. */
    fun fractionNearest(other: Point, target: Point): Double {
        val runEast = other.east - east
        val runNorth = other.north - north
        val lengthSquared = runEast * runEast + runNorth * runNorth
        if (lengthSquared <= EPSILON_METERS * EPSILON_METERS) return 0.0
        val projected = ((target.east - east) * runEast + (target.north - north) * runNorth) / lengthSquared
        return projected.coerceIn(0.0, 1.0)
    }

    fun along(other: Point, fraction: Double): Point =
        Point(east + (other.east - east) * fraction, north + (other.north - north) * fraction)
}

/**
 * Metres east and north of one origin, so the matching is done in flat geometry.
 *
 * A Segment is hundreds of metres of one street. Over that, the ground is flat to well inside a
 * centimetre, and flat is what lets a fix be *projected onto a line* at all — the question the whole
 * of the matching is made of, and one that has no cheap answer on an ellipsoid. The scaling is taken
 * at the Segment's own start on the WGS84 ellipsoid rather than on a sphere, so the two ways this
 * app measures ground ([com.example.runningapp.recording.geodesicDistanceMeters]) do not disagree
 * about a Segment's length by the third of a percent a spherical earth would cost.
 *
 * Fixes far from the origin are distorted, and it does not matter: they are hundreds of metres from
 * a gate that is thirty metres wide, and they are refused either way.
 */
private class LocalFrame(origin: MapFix) {

    private val originLatitude = origin.latitude
    private val originLongitude = origin.longitude
    private val metersPerDegreeLatitude: Double
    private val metersPerDegreeLongitude: Double

    init {
        val latitude = originLatitude * PI / 180.0
        val a = WGS84_SEMI_MAJOR_AXIS
        val eccentricitySquared = 1.0 - (WGS84_SEMI_MINOR_AXIS / a) * (WGS84_SEMI_MINOR_AXIS / a)
        val w = 1.0 - eccentricitySquared * sin(latitude) * sin(latitude)
        // The meridional and normal radii of curvature at this latitude — how far a degree of
        // latitude and a degree of longitude are on the ground here.
        metersPerDegreeLatitude = PI / 180.0 * a * (1.0 - eccentricitySquared) / (w * sqrt(w))
        metersPerDegreeLongitude = PI / 180.0 * a / sqrt(w) * cos(latitude)
    }

    fun project(latitude: Double, longitude: Double): Point = Point(
        east = degreesOfLongitudeFromOrigin(longitude) * metersPerDegreeLongitude,
        north = (latitude - originLatitude) * metersPerDegreeLatitude,
    )

    /** Signed, and the short way round, so a Segment on the date line is not half a planet wide. */
    private fun degreesOfLongitudeFromOrigin(longitude: Double): Double {
        var degrees = longitude - originLongitude
        while (degrees > 180.0) degrees -= 360.0
        while (degrees < -180.0) degrees += 360.0
        return degrees
    }

    private companion object {
        // The same ellipsoid [com.example.runningapp.recording.geodesicDistanceMeters] measures on.
        const val WGS84_SEMI_MAJOR_AXIS = 6378137.0
        const val WGS84_SEMI_MINOR_AXIS = 6356752.3142
    }
}
