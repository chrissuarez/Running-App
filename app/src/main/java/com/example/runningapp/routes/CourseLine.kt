package com.example.runningapp.routes

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.recording.FLAT_EPSILON_METERS
import com.example.runningapp.recording.FlatPoint
import com.example.runningapp.recording.LocalFrame
import com.example.runningapp.recording.geodesicDistanceMeters

/**
 * How much nearer one place on the course has to be than another before the two are told apart, on a
 * reading taken against the whole line (#57).
 *
 * An out-and-back course has two pieces of line under the same patch of ground — the way out and the
 * way back — and on a there-and-back-again file they are near to the *same fraction of a metre*.
 * Something has to break that tie, and before the runner has reached the course the honest tie-break
 * is that they have run none of it yet: the earlier of two equally near places is the one they are
 * standing on, and the whole course is still to go.
 *
 * A metre of slack rather than exact equality, because two pieces of line drawn from the same GPX
 * points are equal only up to the arithmetic. Wider would start claiming a runner who genuinely
 * joins the course near its far end is at the near end instead; that is what this tolerance buys and
 * what it costs.
 */
private const val EQUALLY_NEAR_METERS = 1.0

/**
 * How near the line the runner has to pass before the course starts being read from where they were
 * a moment ago rather than from the whole of it.
 *
 * The thirty metres the spec arms off-course detection at (#52), and the same fact underneath: until
 * the runner has actually been on the course, nothing about where they are says which part of it
 * they are on. A Run starts at the runner's door, and the walk or jog from the door to the start of
 * the course may be a street away from the line, alongside it, or — on an out-and-back — exactly as
 * near its far half as its near one. Every one of those fixes is read against the whole course, so
 * the moment they do reach it, they are placed where they really are.
 *
 * After that the window takes over and never gives way again, however far off the runner strays. A
 * runner who has reached the course and then left it is *somewhere they ran to*, and reading them
 * against the whole line again is exactly the mistake this exists to prevent.
 */
private const val REACHED_THE_COURSE_METERS = 30.0

/**
 * How far along the course the next fix is looked for, either side of where the last one landed.
 *
 * The whole point of looking near the last place rather than at the whole line: an out-and-back
 * passes the same spot twice, and the runner is on the piece they have run to and not on whichever
 * is nearer. Once a fix has landed, the next one is read from around it.
 *
 * [AHEAD_METERS] is over a minute of hard running, so a gap in the fixes — a Pause, a tunnel, a
 * phone in a pocket that lost the sky — does not lose the place. [BEHIND_METERS] is the smaller
 * because going backwards along a course is the rarer thing: a runner who overshoots a turn comes
 * back a few tens of metres, while the doubling back of an out-and-back is *forward* along the line,
 * not backward.
 *
 * A runner further off the course than this reads as a long way from the line, which is the true
 * answer — they are. It is also the answer the off-course ticket wants, and the reason that ticket
 * reuses this module rather than measuring for itself.
 */
private const val AHEAD_METERS = 500.0
private const val BEHIND_METERS = 100.0

/** Where a fix sits on the course: how far along, how much is left, and how far off the line (#57). */
data class CourseProgress(
    /** Ground from the course's start to the nearest point on it, going the way the Run set out. */
    val alongMeters: Double,
    /** What is left of the course from there — the number the live map shows. */
    val remainingMeters: Double,
    /**
     * How far the fix is from the line itself.
     *
     * Nothing here judges whether that is too far. A Run either set out on a course or it did not,
     * and how far off it the runner may stray before anything is said about it belongs to the ticket
     * that says it.
     */
    val metersFromCourse: Double,
    /**
     * Whether the runner has been on the course by now — this fix or any before it.
     *
     * Not a judgement that they are on it *now*, which is a thing nothing in the app claims yet: it
     * is the plain fact that they have reached it at least once, and it is what decides whether the
     * next fix is read from around this one or against the whole course again
     * ([REACHED_THE_COURSE_METERS]).
     */
    val hasReachedTheCourse: Boolean,
)

/**
 * A Route's line as the Run is taking it, and the one place that answers where a fix sits on it
 * (#57).
 *
 * Pure, and scripted in [com.example.runningapp.routes.CourseLineTest] with fixes written in metres:
 * everything the runner is told about a routed Run — how far is left today, and being told they have
 * left the course tomorrow — is this answer, read off a screen or spoken. So it is measured here,
 * once, and never again by a screen.
 *
 * **Direction is not this class's question.** [points] arrive in the order the Run is running them,
 * reversed already if the runner said they were setting off the other way round
 * ([com.example.runningapp.data.SessionRepository.routeLineForRunFlow]). A line measured from its
 * own first point is the same maths whichever way round the runner turned it, and reversing in two
 * places is how the two would come to disagree.
 *
 * The ground is flattened a leg at a time rather than all at once
 * ([com.example.runningapp.recording.LocalFrame]): a Route is kilometres long, and one frame pinned
 * at its start would be a tenth of a percent out at the far end of it — ten metres of a ten-kilometre
 * course, which is exactly the number on the screen. So each leg gets a frame at its own start,
 * where the flattening is true to a centimetre, and the ground *along* the course is measured with
 * [geodesicDistanceMeters] — the same function that measured the Route's distance when it was
 * imported ([routeDistanceMeters]), so "1.2 km to go" and the distance on the Route's own row are
 * two ends of the same measurement.
 */
class CourseLine private constructor(private val legs: List<Leg>) {

    /** How far the whole course goes — the same number [routeDistanceMeters] banked on the row. */
    val lengthMeters: Double = legs.lastOrNull()?.let { it.alongAtStart + it.meters } ?: 0.0

    /** A course nothing has been measured against yet: at its start, with all of it to go. */
    private val startOfTheCourse =
        CourseProgress(0.0, lengthMeters, metersFromCourse = 0.0, hasReachedTheCourse = false)

    /**
     * Where [latitude], [longitude] sits on the course, given where the fix before it landed.
     *
     * [previous] is null for the first fix of the Run. Which of the two readings this is — the whole
     * line, or the ground around [previous] — is decided by [REACHED_THE_COURSE_METERS], and what
     * each is worth is argued there and at [AHEAD_METERS].
     */
    fun progressAt(latitude: Double, longitude: Double, previous: CourseProgress?): CourseProgress {
        val anchor = previous?.takeIf { it.hasReachedTheCourse }
        val low = if (anchor != null) anchor.alongMeters - BEHIND_METERS else 0.0
        val high = if (anchor != null) anchor.alongMeters + AHEAD_METERS else lengthMeters
        // Against the whole line, the earlier of two equally near places wins, so an out-and-back
        // reads as "not started" rather than "nearly home". Around a previous fix the nearest simply
        // wins: which half of the course the runner is on is already settled.
        val mustBeatBestBy = if (anchor != null) 0.0 else EQUALLY_NEAR_METERS

        var bestAlong = 0.0
        var bestOffset = Double.MAX_VALUE
        // Straight to the first leg the window reaches and no further than its last, rather than
        // walking the whole course for every fix. A Run is an hour of fixes and a course is
        // thousands of legs, and this is worked out again on the screen's thread every time a fix
        // lands: the walk has to be over the few hundred metres of course the window covers.
        for (index in firstLegEndingAtOrAfter(low) until legs.size) {
            val leg = legs[index]
            if (leg.alongAtStart > high) break
            val fix = leg.frame.project(latitude, longitude)
            val fraction = leg.start.fractionNearest(leg.end, fix)
            val offset = leg.start.along(leg.end, fraction).metersTo(fix)
            if (offset < bestOffset - mustBeatBestBy) {
                bestOffset = offset
                bestAlong = leg.alongAtStart + fraction * leg.meters
            }
        }
        // The window always holds the leg [previous] landed on, and a whole-line reading holds every
        // leg — so this is the answer for a course of no ground, which [of] refuses to build. Read
        // against the whole line rather than trusted, because a place nothing was measured from is
        // not a place.
        if (bestOffset == Double.MAX_VALUE) {
            return if (anchor != null) progressAt(latitude, longitude, previous = null) else startOfTheCourse
        }
        return CourseProgress(
            alongMeters = bestAlong,
            remainingMeters = (lengthMeters - bestAlong).coerceAtLeast(0.0),
            metersFromCourse = bestOffset,
            hasReachedTheCourse = previous?.hasReachedTheCourse == true ||
                bestOffset <= REACHED_THE_COURSE_METERS,
        )
    }

    /**
     * The first leg with any ground at or beyond [along] — a binary search, the legs being in the
     * order the course runs and each one ending where the next begins.
     */
    private fun firstLegEndingAtOrAfter(along: Double): Int {
        var low = 0
        var high = legs.size
        while (low < high) {
            val middle = (low + high) / 2
            if (legs[middle].alongAtStart + legs[middle].meters < along) low = middle + 1 else high = middle
        }
        return low
    }

    /** One straight piece of the course, flattened about its own start. */
    private class Leg(
        val frame: LocalFrame,
        val start: FlatPoint,
        val end: FlatPoint,
        val alongAtStart: Double,
        val meters: Double,
    )

    companion object {
        /**
         * The course [points] describe, or null when they describe no ground to run — an empty
         * Route, a Route deleted from the library mid-Run, or a file whose points all sit on the
         * same spot. A screen with no course has nothing to say about how far is left.
         *
         * Legs of no ground are dropped rather than kept and skipped over: a course is a list of
         * places and files repeat them, and a leg with no length has no direction to project onto.
         */
        fun of(points: List<RoutePoint>): CourseLine? {
            val legs = mutableListOf<Leg>()
            var along = 0.0
            for ((from, to) in points.zipWithNext()) {
                val meters = geodesicDistanceMeters(
                    from.latitude, from.longitude, to.latitude, to.longitude,
                )
                if (meters <= FLAT_EPSILON_METERS) continue
                val frame = LocalFrame(from.latitude, from.longitude)
                legs += Leg(
                    frame = frame,
                    start = frame.project(from.latitude, from.longitude),
                    end = frame.project(to.latitude, to.longitude),
                    alongAtStart = along,
                    meters = meters,
                )
                along += meters
            }
            return if (legs.isEmpty()) null else CourseLine(legs)
        }
    }
}

/**
 * How much of the course is left after every fix the Run has recorded so far (#57), or null when
 * there is no course to have any of left.
 *
 * The whole Track each time rather than the last fix against a kept answer, because the answer is
 * *derived*: the map card is built and thrown away as the runner moves between screens, and a
 * remembered place would have to survive that or start lying. Reading the whole Track costs a few
 * hundred arithmetic operations per fix — each fix looks at the legs within half a kilometre of the
 * last one and no further — which is nothing beside drawing the map it sits on.
 *
 * A course with no fixes yet is the whole course still to go. That is the honest answer *and* the
 * one that does not flicker: the card appears the moment START is pressed, and the first fix good
 * enough to draw can be many seconds after it.
 *
 * [fixes] are the Run's accuracy-accepted fixes in the order they were recorded — the same list the
 * trail is drawn from, and the same [MapFix] a Segment's ground is read as.
 */
fun courseRemainingMeters(course: CourseLine?, fixes: List<MapFix>): Double? {
    if (course == null) return null
    var progress: CourseProgress? = null
    for (fix in fixes) {
        progress = course.progressAt(fix.latitude, fix.longitude, progress)
    }
    return progress?.remainingMeters ?: course.lengthMeters
}
