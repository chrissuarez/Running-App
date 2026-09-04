package com.example.runningapp.routes

import com.example.runningapp.segments.RunShape
import com.example.runningapp.segments.runsMatch
import kotlin.math.abs

/**
 * Which saved course a Run covered the ground of (#74).
 *
 * The join the library and the matched groups were built either side of. A group of Matched Runs
 * knows the ground was covered more than once but has no name for it (#73); a Route has a name and a
 * page but, until this, only knew the Runs the app had *written down* on it — the ones that picked
 * it before START (#56) and the one it was traced off (#55). So a course imported today opened on an
 * empty page even where the runner had run it fifty times, and every one of those fifty Runs said
 * "this route" about a course sitting in the library with a name on it.
 *
 * **Recognised, and still never guessed at.** This is not the app deciding what a Run set out to do:
 * a Routed Run is a Run that picked a course, that is written at START, and nothing here writes it or
 * ever will (`RunnerSession.ranAlongRouteId`, CONTEXT.md). What is claimed here is smaller and
 * checkable — this Run covered this course's ground — and it is claimed by the same rule that decides
 * two Runs covered each other's ([runsMatch]), against a course reduced by the same sampler a Run is
 * ([routeShapeOf]).
 *
 * **Nothing is stored.** The link is worked out on every read, off the two shapes, which is what
 * gives the ticket its last two lines for free: a course saved today claims the Runs that already fit
 * it at the next read, and a course deleted stops claiming them without touching a single Run.
 */

/** One saved course, as the recognising asks about it: which it is, what it is called, and its shape. */
data class CourseShape(
    val routeId: Long,
    val name: String,
    val shape: RunShape,
)

/**
 * Whether this Run covered this course's ground.
 *
 * [runsMatch] and its tolerances exactly — the same hundred metres at the ends, the same twentieth on
 * the totals, the same order along the way — because the runner's question is the same question. A
 * separate set of numbers for courses would let a Run be in a group of Matched Runs whose route's own
 * page disowned it.
 *
 * **Either way round.** A course is a line with two ends and no arrow on it: the app has let a runner
 * set out along one backwards since #56 and calls that Run a Run on the course, so a Run recognised
 * on it backwards is one too. That is not the loop rule being broken — [runsMatch] refuses a *Run*
 * run the other way round because two recordings the runner could tell apart should not be called one
 * outing, whereas here one of the two sides is a plan the runner may run in either direction.
 * Reversing the course rather than the Run keeps the comparison symmetric: the waypoints are evenly
 * spaced by ground, so a reversed shape is what the same course drawn the other way would have given.
 */
fun runIsOnCourse(run: RunShape, course: RunShape): Boolean =
    runsMatch(run, course) || runsMatch(run, course.theOtherWayRound())

/**
 * The same course drawn from the other end.
 *
 * The waypoints alone, reversed, and the distance untouched: they sit at even fractions of the
 * course's length, so reading them backwards is exactly the list the line drawn the other way would
 * have produced.
 */
fun RunShape.theOtherWayRound(): RunShape = copy(waypoints = waypoints.reversed())

/**
 * The saved course this Run covered, or null where it covered none.
 *
 * **Where more than one course fits, the closest in length wins**, and an exact tie goes to the
 * course kept first. Two courses can fit one Run — a library that kept the same ground twice (#402),
 * or two lengths of one family whose difference falls inside the tolerances — and the runner is owed
 * one name rather than a list or a silence. Length is the tie-break because it is the one number the
 * two courses differ by that the runner can see for themselves on the library row. The rule is total,
 * so two reads of the same library name the same course.
 *
 * **Choosing one here does not take the Run off the other course's page, and must not.** This answers
 * "what shall I call this ground", which is a sentence and holds one name; a course's page answers
 * "which Runs covered my ground", and both duplicates really were covered. A Run listed on two pages
 * while its own card names one is the honest reading of a library that holds the same ground twice —
 * hiding it from one of them would be the app deciding which of the runner's two courses is the real
 * one, which is exactly the guess #402 exists to let them settle themselves.
 */
/**
 * Which course a *group* of Runs may be named after (#74).
 *
 * Every Run shown on the card has to be on the course before its name goes above them, because the
 * card's own count says so: "3 runs on the Cuckoo Trail" is a claim about all three, and the
 * course's page applies [runIsOnCourse] to each of them one at a time. Naming the group off
 * [subject] alone would print that sentence over a Run the page then left out.
 *
 * A group is not a set of Runs that all agree with each other — [runsMatch] is a tolerance, not an
 * equality, so it does not carry from one pair to the next. Two Runs at opposite edges of the same
 * hundred metres are both on [subject] and need not both be on any one course.
 *
 * The winner among the courses that do take the whole group is chosen by [courseRecognising], off
 * [subject] — the Run whose page this is, and the one length the card is about.
 */
fun courseRecognisingGroup(
    subject: RunShape,
    group: List<RunShape>,
    courses: List<CourseShape>,
): CourseShape? =
    courseRecognising(subject, courses.filter { course -> group.all { runIsOnCourse(it, course.shape) } })

fun courseRecognising(run: RunShape, courses: List<CourseShape>): CourseShape? = courses
    .filter { runIsOnCourse(run, it.shape) }
    .minWithOrNull(
        compareBy<CourseShape> { abs(it.shape.distanceMeters - run.distanceMeters) }
            .thenBy { it.routeId }
    )
