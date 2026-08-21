package com.example.runningapp.segments

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.MeasuredTrack
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.recording.geodesicDistanceMeters
import kotlin.math.abs
import kotlin.math.min

/**
 * Whether two Runs went over the same ground (#73).
 *
 * The other half of the matching this package already does. A Segment asks whether one Run crossed
 * one named stretch; this asks whether two whole Runs are the same outing done twice — the loop
 * round the park, the way to work — so that repeating a route becomes something the runner can see
 * rather than something they have to remember.
 *
 * Nothing is named and nothing is saved: there is no Route here and the runner is never asked to
 * make one. Two Runs recognise each other by their shape alone, which is Strava's own answer to the
 * same question and the only one that can reach backwards over a history nobody was tagging as they
 * ran it.
 *
 * Pure and scripted, for [segmentTraversalsIn]'s reason: every constant below is a boundary, and a
 * boundary pinned by a recorded Run is a boundary pinned by whatever that Run happened to do.
 * [com.example.runningapp.segments.RunMatchingTest] writes tracks in metres and says which pairs
 * should find each other.
 */

/**
 * A Run reduced to the few numbers that say which ground it covered.
 *
 * [waypoints] are [RUN_SHAPE_WAYPOINTS] places evenly spaced by *distance covered*, first one the
 * start and last one the end. That spacing is what makes the list answer all four of the questions a
 * match asks at once: the ends are the start and the end, and the ones between them are the order
 * the ground was covered in — which is the whole of what "the same way round" means. A loop run
 * backwards has the same two ends and its middle waypoints mirrored, and mirrored is what this
 * refuses.
 *
 * Spaced by distance rather than by time on purpose. A shape must be the same shape whether the
 * runner jogged it or raced it, and time would put the waypoints of a Run that stopped at the lights
 * in different places from the same route run straight through.
 */
data class RunShape(
    val waypoints: List<MapFix>,
    /** How far the Run went, as everything else in the app counts it — the sum of its legs. */
    val distanceMeters: Double,
)

/**
 * How many places a Run is reduced to.
 *
 * Five: the two ends, the half way, and a quarter either side of it. Fewer than five and an
 * out-and-back with a loop bolted on the end would pass as the plain out-and-back; many more and
 * every waypoint is a fresh chance for one bad fix to refuse a match the runner can see with their
 * own eyes on the map.
 */
const val RUN_SHAPE_WAYPOINTS: Int = 5

/**
 * How short a Run may be and still have a shape worth matching on.
 *
 * Under half a kilometre the tolerances below are a large fraction of the Run itself, so everything
 * matches everything: a two-hundred-metre Run down the road and a two-hundred-metre Run round the
 * block would be one route. Such a Run holds no route worth calling repeated, so it holds no shape
 * at all and simply never appears in anybody's group.
 */
const val RUN_SHAPE_MINIMUM_METERS: Double = 500.0

/**
 * How far apart two Runs' starts, or two Runs' ends, may be.
 *
 * A hundred metres is the length of a street and about the width of the doubt GPS leaves on the
 * first fix of a Run — the one taken as the phone comes out of a pocket, before the receiver has
 * settled. Tighter and starting from the far side of the road refuses the match; looser and the
 * gate reaches the next junction, which is a different way out of the house.
 */
const val RUN_MATCH_END_METERS: Double = 100.0

/**
 * How far apart the waypoints between the ends may be, as a fraction of the shorter Run.
 *
 * Proportional because that is what the waypoints themselves are: they sit at fractions of the
 * distance covered, so two Runs whose totals differ by two per cent have their middles two per cent
 * of a Run apart before either of them has been anywhere different. Fixed metres here would refuse
 * long Runs for being long.
 */
const val RUN_MATCH_COURSE_FRACTION: Double = 0.05

/** The least the proportional tolerance above may come to, so a short Run keeps GPS's own doubt. */
const val RUN_MATCH_COURSE_FLOOR_METERS: Double = 100.0

/**
 * How far apart two Runs' totals may be, as a fraction of the shorter of them.
 *
 * This is the rule that keeps a longer Run out of the group when it merely passes through the same
 * start and the same end: five kilometres round the park and the same five with a second lap of the
 * pond in the middle are not the same Run, however alike their ends are.
 */
const val RUN_MATCH_DISTANCE_FRACTION: Double = 0.05

/**
 * The shape of a finished Run's track, or null where there is no shape to take.
 *
 * Measured off the same legs everything else reads a Run's shape from ([MeasuredTrack]), so the
 * ground counted here is the ground printed at the top of the Run's own page: a Pause carries none
 * and an Outage carries the straight line across it (ADR 0010).
 *
 * Null for a Run with fewer than two fixes — a treadmill Run, or history from before there was a
 * track — and for one under [RUN_SHAPE_MINIMUM_METERS].
 */
fun runShapeOf(measured: MeasuredTrack): RunShape? {
    val points = measured.points
    if (points.size < 2 || measured.legs.size < points.size - 1) return null

    val along = DoubleArray(points.size)
    for (leg in measured.legs.indices) along[leg + 1] = along[leg] + measured.legs[leg].meters
    val total = along.last()
    if (total < RUN_SHAPE_MINIMUM_METERS) return null

    val waypoints = (0 until RUN_SHAPE_WAYPOINTS).map { at ->
        fixAt(points, along, total * at / (RUN_SHAPE_WAYPOINTS - 1))
    }
    return RunShape(waypoints = waypoints, distanceMeters = total)
}

/**
 * Whether two Runs went over the same ground, the same way round, for the same distance.
 *
 * "The same way round" is a claim about the ground, not about the runner's heading. A loop run
 * backwards has its middle waypoints mirrored and is refused; an *out-and-back* run the other way
 * round is not, and must not be — reversing an out-and-back retraces the very same line past the
 * very same places, so the two Runs really are the same route and the runner would say so.
 *
 * Symmetric, and every tolerance is taken against the shorter of the two, so a Run cannot match a
 * second one and be refused by it. That matters more than it looks: the group a page shows is every
 * Run that matches the one being looked at, and a rule that answered differently depending on which
 * Run was asked would give a runner two different histories of one route.
 */
fun runsMatch(one: RunShape, other: RunShape): Boolean {
    if (one.waypoints.size != other.waypoints.size) return false

    val shorter = min(one.distanceMeters, other.distanceMeters)
    if (abs(one.distanceMeters - other.distanceMeters) > RUN_MATCH_DISTANCE_FRACTION * shorter) return false

    val courseMeters = maxOf(RUN_MATCH_COURSE_FLOOR_METERS, RUN_MATCH_COURSE_FRACTION * shorter)
    return one.waypoints.indices.all { at ->
        val isEnd = at == 0 || at == one.waypoints.lastIndex
        val apart = metersBetween(one.waypoints[at], other.waypoints[at])
        apart <= if (isEnd) RUN_MATCH_END_METERS else courseMeters
    }
}

/**
 * Whether a Run may be matched to other Runs at all.
 *
 * The same three conditions a Segment effort asks ([mayHoldSegmentEfforts]), asked in the one place
 * they are written: a Run still being recorded has half a shape, a Walk is not the runner running
 * this route, and a treadmill Run has no ground to have covered. Marking a Run a Walk therefore
 * takes it out of every group it was in, and unmarking puts it back — the rule the Segments keep.
 */
fun RunnerSession.mayBeMatchedToOtherRuns(): Boolean = mayHoldSegmentEfforts()

/** Where the Run was when it had covered [target] metres, interpolated along the leg it fell on. */
private fun fixAt(
    points: List<TrackPoint>,
    along: DoubleArray,
    target: Double,
): MapFix {
    if (target <= 0.0) return MapFix(points.first().latitude, points.first().longitude)
    for (leg in 0 until points.lastIndex) {
        val legMeters = along[leg + 1] - along[leg]
        if (legMeters <= 0.0) continue
        if (target > along[leg + 1]) continue
        val fraction = ((target - along[leg]) / legMeters).coerceIn(0.0, 1.0)
        val from = points[leg]
        val to = points[leg + 1]
        return MapFix(
            latitude = from.latitude + (to.latitude - from.latitude) * fraction,
            longitude = from.longitude + (to.longitude - from.longitude) * fraction,
        )
    }
    return MapFix(points.last().latitude, points.last().longitude)
}

private fun metersBetween(one: MapFix, other: MapFix): Double =
    geodesicDistanceMeters(one.latitude, one.longitude, other.latitude, other.longitude)
