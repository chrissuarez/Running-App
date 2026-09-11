package com.example.runningapp.ui

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.recording.FLAT_EPSILON_METERS
import com.example.runningapp.recording.LocalFrame
import com.example.runningapp.recording.theShortWayRound
import com.example.runningapp.run.RunRoute
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

// Which way round a course goes: the arrows its page draws along it (#465), and the one sum that
// turns the pre-run switch into what a Run writes down (#466).

/** One arrow on a course's map: where it sits, and which way the course is heading there. */
data class DirectionArrow(
    val at: MapFix,
    /** Degrees clockwise from north — what Mapbox turns an icon by. */
    val bearingDegrees: Double,
)

/** Roughly how much ground each arrow stands for, before the count is held inside its bounds. */
private const val MetersPerArrow = 600.0
private const val FewestArrows = 3
private const val MostArrows = 8

/**
 * How far either side of an arrow the heading is read across.
 *
 * Wider than one leg, because a GPX file's legs can be a metre long and wobble: the heading of the
 * one leg an arrow happens to land on could point across the street. Thirty metres of ground says
 * which way the course is going there.
 */
private const val HeadingSpanMeters = 15.0

/**
 * The arrows drawn along [line], which is already in the order the course is run
 * ([theWayRoundItIsRun]).
 *
 * Spread evenly by ground, and never on either end — the start and the finish have markers of their
 * own, and on a loop they are one place, where an arrow would sit on top of both. Three on a short
 * course, one per [MetersPerArrow] after that, and never more than eight: enough to read on a map
 * two hundred pixels tall without turning the line into a fence.
 *
 * Empty for a line with nothing to point along — no fixes, one fix, or every fix in one place.
 */
fun directionArrowsAlong(line: List<MapFix>): List<DirectionArrow> {
    if (line.size < 2) return emptyList()
    // How far along the course each fix is, each leg measured in a frame pinned at its own start —
    // the way a course is flattened everywhere else in the app (CourseLine), because a course can be
    // a half marathon and one frame over all of it would bend.
    val along = DoubleArray(line.size)
    for (i in 1 until line.size) {
        along[i] = along[i - 1] + metersOf(line[i - 1], line[i])
    }
    val total = along.last()
    if (total <= FLAT_EPSILON_METERS) return emptyList()

    val count = (total / MetersPerArrow).roundToInt().coerceIn(FewestArrows, MostArrows)
    val span = minOf(HeadingSpanMeters, total / (count + 1) / 4)
    return (1..count).mapNotNull { k ->
        val target = total * k / (count + 1)
        val behind = fixAt(line, along, target - span)
        val ahead = fixAt(line, along, target + span)
        bearingDegrees(behind, ahead)?.let { DirectionArrow(fixAt(line, along, target), it) }
    }
}

/**
 * This course's line in the order it is run: as kept, or the other way where the runner flipped it
 * ([com.example.runningapp.data.Route.flipped]).
 */
fun <T> List<T>.theWayRoundItIsRun(flipped: Boolean): List<T> = if (flipped) asReversed() else this

/**
 * The course a Run sets out on, given the pre-run switch (#466).
 *
 * [backwards] is the switch, which is against the course's *usual* way; what the Run writes down is
 * against the line *as kept* ([com.example.runningapp.data.RunnerSession.ranAlongRouteReversed]).
 * The two differ exactly where the course is flipped, and this is the one place that says so — so a
 * flip made later never changes which way an old Run says it went.
 */
fun runRouteSetOutAlong(route: RouteHeader, backwards: Boolean): RunRoute =
    RunRoute(route.id, reversed = backwards != route.flipped)

/**
 * The words under a course's map, saying what its arrows mean and whether it has been turned round.
 *
 * "Saved" rather than "drawn", because a course can be traced off a Run as well as imported, and a
 * course saved from a Run was never drawn by anyone.
 */
fun routeDirectionLine(flipped: Boolean): String =
    if (flipped) {
        "Arrows show the way you'll run it — flipped from the way it was saved."
    } else {
        "Arrows show the way you'll run it — the way it was saved."
    }

/** The button that turns a course round for good. */
const val FLIP_ROUTE_BUTTON_LABEL = "Flip direction"

private fun metersOf(from: MapFix, to: MapFix): Double =
    LocalFrame(from.latitude, from.longitude).project(to.latitude, to.longitude)
        .let { flat -> hypot(flat.east, flat.north) }

/** Degrees clockwise from north, from [from] to [to]; null where the two are one place. */
private fun bearingDegrees(from: MapFix, to: MapFix): Double? {
    val flat = LocalFrame(from.latitude, from.longitude).project(to.latitude, to.longitude)
    if (hypot(flat.east, flat.north) <= FLAT_EPSILON_METERS) return null
    val degrees = Math.toDegrees(atan2(flat.east, flat.north))
    return if (degrees < 0.0) degrees + 360.0 else degrees
}

/**
 * Where the course is [target] metres along it, interpolated on the leg it falls on — clamped to the
 * ends. Longitude is interpolated the short way round, so a leg over the date line stays on it.
 */
private fun fixAt(line: List<MapFix>, along: DoubleArray, target: Double): MapFix {
    if (target <= 0.0) return line.first()
    for (leg in 0 until line.lastIndex) {
        val legMeters = along[leg + 1] - along[leg]
        if (legMeters <= 0.0 || target > along[leg + 1]) continue
        val fraction = ((target - along[leg]) / legMeters).coerceIn(0.0, 1.0)
        val from = line[leg]
        val to = line[leg + 1]
        return MapFix(
            latitude = from.latitude + (to.latitude - from.latitude) * fraction,
            longitude = theShortWayRound(
                from.longitude + theShortWayRound(to.longitude - from.longitude) * fraction
            ),
        )
    }
    return line.last()
}
