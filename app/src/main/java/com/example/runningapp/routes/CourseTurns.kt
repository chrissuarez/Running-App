package com.example.runningapp.routes

import com.example.runningapp.analysis.thinnedLineIndices
import com.example.runningapp.recording.LocationFix
import com.example.runningapp.recording.METERS_PER_DEGREE
import com.example.runningapp.recording.SessionRecorder
import com.example.runningapp.recording.degreesEastOf
import com.example.runningapp.recording.geodesicDistanceMeters
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * How far a place may sit from the line drawn without it before the turn-finding keeps it (#456).
 *
 * The turns are looked for on the course's *shape*, not on its every place, and this is the detail
 * that shape is drawn to. Ten metres, which is five times the detail the row's own line is kept to
 * (two metres, `ROUTE_DETAIL_METERS`) and for a different question: a line kept to two metres is a
 * line good enough to follow, and a runner's own track kept to two metres still wanders a metre
 * either side of the road down every straight. Consecutive legs of it
 * differ by tens of degrees over a couple of metres of ground, so read place by place *every*
 * straight is a street of tiny turns. Drawn to ten metres, a straight road is a straight line and a
 * corner is still a corner: no path bends by a right angle and back inside ten metres without that
 * being a real thing on the ground.
 *
 * It is the line the *turns* are read off, and nothing else. The row's line is untouched, the
 * distance remaining is still measured along every place of it ([CourseLine]), and where a turn sits
 * is reported as ground along that same full line — see [courseTurnsOf].
 */
private const val TURN_DETAIL_METERS = 10.0

/**
 * How sharply the course has to bend before the bend is a turn worth a sentence.
 *
 * Stated from the side the app speaks: **a bend of forty-five degrees or more is a turn**, and
 * anything gentler is a bend the runner takes without deciding anything. A road curving round a park
 * changes direction by ninety degrees over half a kilometre and needs no telling; a street corner
 * does it in a stride. Forty-five is comfortably below the ninety of a corner and comfortably above
 * the ten or twenty a road's own curve gives up over the ten metres of [TURN_DETAIL_METERS], so the
 * boundary case — a fork in a path, at forty-five degrees exactly — is announced, on purpose.
 */
private const val TURN_DEGREES = 45.0

/**
 * How far before a turn the runner is told about it.
 *
 * Fifty metres is some ten seconds of running: long enough to look up and find the turning, short
 * enough that the turning being talked about is the one in front of them and not the one after it.
 * It is the distance the sentence itself names, so the sentence is written from this number rather
 * than beside it ([TurnCue.spoken]).
 */
private const val TURN_WARNING_METERS = 50.0

/**
 * How near two turns have to be before they are one instruction.
 *
 * A roundabout, a chicane, a dog-leg round a building are several bends and one decision, and told
 * one at a time they are four sentences in six seconds — which is worse than silence, because the
 * runner is listening to the third one while they run past the first.
 *
 * The number is [TURN_WARNING_METERS], and deliberately the same one rather than a second number of
 * its own: a turn nearer to the one before it than the warning distance would have its own warning
 * spoken *before* the first turn was even reached. Merging at exactly that distance is what stops
 * two instructions ever being in the air at once, and no smaller number can promise it.
 *
 * Stated from the side that merges: turns **less than** fifty metres apart are one cue, and turns
 * fifty metres apart or more are two. At fifty metres exactly the second turn's warning lands on the
 * first turn itself — the two are back to back, in order, and each is about ground the runner has
 * not yet covered.
 */
private const val TURNS_TOGETHER_METERS = TURN_WARNING_METERS

/**
 * How far past a cue's own ground the runner may be and still be told.
 *
 * Fixes land a few metres apart, so this never bites on a Run going normally: it is for the gap — a
 * pocket that lost the sky, a Pause, a wrong turn that put the runner off the line for a street —
 * after which the next fix can land hundreds of metres further along the course. A "Turn left in
 * fifty metres" about a corner already behind the runner is not a late cue, it is a wrong one.
 *
 * Under [TURN_WARNING_METERS] on purpose, so a warning can never still be waiting to be said once
 * the turn it warns about has been reached — by then the turn's own cue is the true sentence, and
 * this is what stops the two arriving together.
 */
private const val TURN_CUE_LATE_METERS = 20.0

/** Which way the course bends, and the word the cue says for it. */
enum class TurnDirection(val spoken: String) {
    LEFT("left"),
    RIGHT("right"),
}

/** Where the course turns, as ground along it, and which way (#456). */
data class CourseTurn(
    /**
     * Ground from the course's start to the turn, measured along the whole stored line — the same
     * measurement [CourseProgress.alongMeters] answers with, so the two can be compared.
     */
    val alongMeters: Double,
    val direction: TurnDirection,
)

/** Which of a turn's two sentences this is. */
enum class TurnCueMoment {
    /** [TURN_WARNING_METERS] before the turn. */
    AHEAD,

    /** At it. */
    AT_THE_TURN,
}

/**
 * One thing the app says about a turn (#456).
 *
 * The words are a fact about the *course* and never about the ground: the app has no map, knows no
 * street names, and cannot see whether the turning is a gate, a gap in a hedge or a wall. It says
 * where the line the runner chose bends, and the runner is the one who can see what is there.
 */
data class TurnCue(val direction: TurnDirection, val moment: TurnCueMoment) : CourseSaying {
    override val spoken: String = when (moment) {
        TurnCueMoment.AHEAD -> "Turn ${direction.spoken} in ${TURN_WARNING_METERS.toInt()} metres."
        TurnCueMoment.AT_THE_TURN -> "Turn ${direction.spoken}."
    }
}

/**
 * The turns of a course, in the order the Run takes them (#456).
 *
 * [points] arrive in the order the Run is running them, reversed already where the runner said they
 * were setting off the other way round — the same list [CourseLine] is built from, and it has to be:
 * a turn is reported as ground *along* the course, and the two would be measuring from opposite ends
 * otherwise. Which way round the course is turned genuinely changes the answer here, unlike every
 * other measurement in this package: a course run backwards turns right where it turned left.
 *
 * Three steps, and each is argued at the number it uses:
 *
 *  - **The shape, not the places.** The bends are read off the line thinned to [TURN_DETAIL_METERS],
 *    because a course saved off a Run holds a place every second or two and every one of them looks
 *    like a tiny turn.
 *  - **A bend has to be a turn.** [TURN_DEGREES] of direction change, or it is a bend in a road.
 *  - **Turns that arrive together are one instruction.** [TURNS_TOGETHER_METERS].
 *
 * **Where a turn sits is measured along the whole line, not along the thinned one.** The thinned
 * line cuts every corner, so ground measured along it falls short of the ground the runner covers —
 * a few metres over a course, which is most of the fifty the warning is worth. So the shape decides
 * *which* places are turns and the full line decides *how far along* each of them is, and the number
 * that comes out is the very number [CourseLine] hands back for a fix.
 *
 * Pure — no clock, no Android — and scripted in [com.example.runningapp.routes.CourseTurnsTest].
 */
fun courseTurnsOf(points: List<RoutePoint>): List<CourseTurn> {
    if (points.size < 3) return emptyList()

    // Metres on a flat sheet, taken once for the whole course, exactly as the shaping in `Course.kt`
    // lays a line out: a degree of longitude shrinks going north, so it is shrunk by the cosine of
    // where the course is, and how far east a place lies is asked of [degreesEastOf] so a course
    // over the date line is laid out the way it is run rather than flung round the world. A course
    // is kilometres, not hundreds, so the sheet is a tenth of a percent out at its far end — which
    // moves no bearing by a degree and cannot turn a corner into a straight.
    val cosLatitude = cos(Math.toRadians(points.first().latitude))
    val x = DoubleArray(points.size) {
        degreesEastOf(points.first().longitude, points[it].longitude) * METERS_PER_DEGREE * cosLatitude
    }
    val y = DoubleArray(points.size) { (points[it].latitude - points.first().latitude) * METERS_PER_DEGREE }

    // Ground along the whole line, place by place — [geodesicDistanceMeters], the function that
    // measured the Route's distance when it was kept and that [CourseLine] measures a fix's progress
    // with, so a turn's `alongMeters` and a fix's `alongMeters` are the same ruler.
    val along = DoubleArray(points.size)
    for (i in 1 until points.size) {
        along[i] = along[i - 1] + geodesicDistanceMeters(
            points[i - 1].latitude, points[i - 1].longitude, points[i].latitude, points[i].longitude,
        )
    }

    val shape = thinnedLineIndices(x, y, TURN_DETAIL_METERS)
    val bends = mutableListOf<Bend>()
    for (k in 1 until shape.size - 1) {
        val before = shape[k - 1]
        val at = shape[k]
        val after = shape[k + 1]
        val change = bearingChangeDegrees(
            inX = x[at] - x[before], inY = y[at] - y[before],
            outX = x[after] - x[at], outY = y[after] - y[at],
        ) ?: continue
        if (abs(change) >= TURN_DEGREES) bends += Bend(along[at], change)
    }
    return bends.mergedWhereTheyArriveTogether()
}

/** One bend of the shape: where it is, and how far round it goes — right positive, left negative. */
private class Bend(val alongMeters: Double, val degrees: Double)

/**
 * The bends with every run of them that arrives together written as one cue
 * ([TURNS_TOGETHER_METERS]).
 *
 * **The cue is put at the first bend of the run**, because that is the ground the runner reaches
 * first and a warning has to be about the turning in front of them. Put at the last, or at the
 * middle of the run, a roundabout would be announced from inside it.
 *
 * **The direction is the sharpest bend's**, not the sum of them. A chicane is a hard left and a hard
 * right and sums to nothing at all, which is no instruction; a roundabout taken most of the way
 * round sums past a half turn and comes out the wrong way about. The sharpest bend of a run is the
 * one the runner would actually miss, and it is the one they are told about. Where two are equally
 * sharp the earlier wins, for the same reason the cue sits at the first: it is the one they meet.
 */
private fun List<Bend>.mergedWhereTheyArriveTogether(): List<CourseTurn> {
    val turns = mutableListOf<CourseTurn>()
    var index = 0
    while (index < size) {
        var last = index
        while (last + 1 < size && this[last + 1].alongMeters - this[last].alongMeters < TURNS_TOGETHER_METERS) {
            last++
        }
        val sharpest = subList(index, last + 1).maxByOrNull { abs(it.degrees) }!!
        turns += CourseTurn(
            alongMeters = this[index].alongMeters,
            direction = if (sharpest.degrees > 0) TurnDirection.RIGHT else TurnDirection.LEFT,
        )
        index = last + 1
    }
    return turns
}

/**
 * How far round the line turns between one leg and the next, in degrees — positive to the right,
 * negative to the left, and null where either leg has no length to have a direction.
 *
 * Held inside half a turn either way, so a hairpin comes out as a hard turn rather than as a gentle
 * one the long way round. A hairpin of exactly half a turn lands on the right, which is arbitrary
 * and has to be: a course that doubles back on itself bends both ways at once, and a runner who has
 * to turn round can see that for themselves whichever word is used.
 */
private fun bearingChangeDegrees(inX: Double, inY: Double, outX: Double, outY: Double): Double? {
    if ((inX == 0.0 && inY == 0.0) || (outX == 0.0 && outY == 0.0)) return null
    // Bearings the compass way — clockwise from north — so that turning right reads positive.
    val into = Math.toDegrees(atan2(inX, inY))
    val outOf = Math.toDegrees(atan2(outX, outY))
    return ((outOf - into + 540.0) % 360.0) - 180.0
}

/**
 * Watches a routed Run against the turns of its course, and says which way it bends before it does
 * (#456).
 *
 * Pure — no clock, no Android, no speech — and scripted in
 * [com.example.runningapp.routes.CourseTurnWatchTest] with fixes written in metres. It is the
 * companion of [OffCourseWatch] and keeps every rule that one keeps, for the reasons argued there:
 *
 * **Silent until the runner has reached the course.** The jog from the front door is not a missed
 * turn. [CourseProgress.hasReachedTheCourse] is the same fact the off-course alerts arm on.
 *
 * **A fix that is not trusted is not heard**, and nothing is said while the Run is auto-paused —
 * standing still is not approaching a turn.
 *
 * **Silent while the runner is off the line.** A runner further than [OFF_COURSE_METERS] from the
 * course is not approaching its turns, they are somewhere else; the app has one sentence for that
 * and this is not it. Their place on the course is still read, so nothing is lost by it.
 *
 * **Nothing is said twice.** The cues are held in the order the course reaches them and a pointer
 * walks that list forwards and never back — so a runner wobbling either side of the trigger point is
 * told once, and an out-and-back that brings them over the same ground the other way is *forward*
 * along the line and reaches the turns beyond it, not the ones behind.
 *
 * **A cue about ground already covered is not said at all** ([TURN_CUE_LATE_METERS]).
 *
 * **Where the runner is on the course is read here as well as by [OffCourseWatch].** Two readings of
 * the one [CourseLine] rather than one shared between them, because the two want different things
 * from it: the off-course watch re-reads the *whole* line while the runner is away from it, so that
 * a rejoin half a kilometre up the course is seen, and that is the reading this must not have — a
 * runner who left the course at its first corner and came back at its last has not turned the corners
 * in between and must not be told about the next one as though they had. Reading from where they
 * last were *on* the course is the anchor this wants, and it costs a few hundred arithmetic
 * operations a second.
 */
class CourseTurnWatch(private val course: CourseLine, turns: List<CourseTurn>) {

    /** Every sentence the course has, in the order the ground reaches its trigger. */
    private val cues: List<CueAt> = turns
        .flatMap {
            listOf(
                CueAt(it.alongMeters - TURN_WARNING_METERS, TurnCue(it.direction, TurnCueMoment.AHEAD)),
                CueAt(it.alongMeters, TurnCue(it.direction, TurnCueMoment.AT_THE_TURN)),
            )
        }
        // Stable, so that where a turn's own cue and the next turn's warning fall on the very same
        // ground — which is exactly what [TURNS_TOGETHER_METERS] leaves standing at fifty metres
        // apart — the turn the runner reaches first is still spoken first.
        .sortedBy { it.alongMeters }

    /** Where the last heard fix landed on the course — the anchor the next one is read from. */
    private var progress: CourseProgress? = null

    /** The first cue not yet considered. It only ever moves forwards. */
    private var nextCue = 0

    /** Whether the runner has reached the course, and the cues behind them been stepped over. */
    private var started = false

    /** Take one fix, and say whatever the turns ahead of the runner have to say about it. */
    fun onFix(fix: LocationFix, autoPaused: Boolean): List<TurnCue> {
        if (autoPaused || !SessionRecorder.isAccuracyAccepted(fix.accuracyMeters)) return emptyList()
        val here = course.progressAt(fix.latitude, fix.longitude, progress)
        progress = here
        if (!here.hasReachedTheCourse) return emptyList()

        // Reaching the course says nothing, however much of it is behind: a runner who joins a loop
        // at its halfway point has not missed the turns of its first half, they are running the
        // second half. Every cue behind them is stepped over without a word.
        if (!started) {
            started = true
            nextCue = cues.indexOfFirst { it.alongMeters > here.alongMeters }.takeIf { it >= 0 } ?: cues.size
            return emptyList()
        }
        if (here.metersFromCourse > OFF_COURSE_METERS) return emptyList()

        val said = mutableListOf<TurnCue>()
        while (nextCue < cues.size && cues[nextCue].alongMeters <= here.alongMeters) {
            val cue = cues[nextCue++]
            if (here.alongMeters - cue.alongMeters <= TURN_CUE_LATE_METERS) said += cue.cue
        }
        return said
    }

    /** One sentence and the ground along the course that earns it. */
    private class CueAt(val alongMeters: Double, val cue: TurnCue)
}
