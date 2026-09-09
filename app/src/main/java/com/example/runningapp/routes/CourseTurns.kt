package com.example.runningapp.routes

import com.example.runningapp.analysis.thinnedLineIndices
import com.example.runningapp.recording.LocationFix
import com.example.runningapp.recording.SessionRecorder
import com.example.runningapp.recording.geodesicDistanceMeters
import kotlin.math.abs
import kotlin.math.atan2

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
 *
 * **Stated from the side the code accepts: a turn's own cue is worth saying while the runner is
 * *less than* twenty metres past the turn, and at twenty metres exactly it is not.** One side, one
 * comparison, and the same one whether the cue is being made or being taken back — because those
 * are the same question asked at two moments, and answering them differently at the boundary is how
 * a cue comes to be made and then never taken back. See [CueAt.falseFromAlongMeters].
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
 * **The walk is bounded by the line it is handed.** [thinnedLineIndices] is quadratic on a line
 * built to defeat it, and what arrives here is a Route's stored line — which `courseOf` has already
 * put through that very walk, under its own bound of twenty thousand places. So this is no bigger
 * a walk than the import that made the row already did, and it is done off the main thread for the
 * reason [courseToWatchFlow] gives.
 *
 * Pure — no clock, no Android — and scripted in [com.example.runningapp.routes.CourseTurnsTest].
 */
fun courseTurnsOf(points: List<RoutePoint>): List<CourseTurn> {
    if (points.size < 3) return emptyList()

    // The very sheet the row's own shaping is laid out on, and shared with it for that reason
    // ([flattenedToMeters]): the two are asking different questions of one line, and they must not
    // be able to lay it out differently while doing so.
    val (x, y) = points.flattenedToMeters()

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
 * **A run is measured from that first bend and not from the bend before it**, so one instruction
 * covers under [TURNS_TOGETHER_METERS] of course and no more. Measured bend to bend a run would
 * chain: a winding trail of bends forty-nine metres apart would fold into a single cue at its first
 * one and say nothing at all for the rest of the trail, which is the opposite of what merging is
 * for. It is also what keeps two instructions out of the air at once — the runs it makes start at
 * least [TURNS_TOGETHER_METERS] apart, so the next run's warning never lands before this run's own
 * cue.
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
        while (last + 1 < size &&
            this[last + 1].alongMeters - this[index].alongMeters < TURNS_TOGETHER_METERS
        ) {
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
 * one the long way round. The half turn itself lands on the **left** — a course that doubles back
 * exactly on itself bends both ways at once, so one of the two words has to be picked arbitrarily,
 * and a runner who has to turn round can see that for themselves whichever word is used. It is the
 * left because the range this lands in is [-180, 180), and that is stated rather than left to be
 * worked out from the arithmetic.
 */
private fun bearingChangeDegrees(inX: Double, inY: Double, outX: Double, outY: Double): Double? {
    if ((inX == 0.0 && inY == 0.0) || (outX == 0.0 && outY == 0.0)) return null
    // Bearings the compass way — clockwise from north — so that turning right reads positive.
    val into = Math.toDegrees(atan2(inX, inY))
    val outOf = Math.toDegrees(atan2(outX, outY))
    return ((outOf - into + 540.0) % 360.0) - 180.0
}

/**
 * One sentence the turns have earned, and the ground it stops being true at (#456).
 *
 * The deadline travels with the sentence because it belongs to that sentence alone. Two turn cues
 * can be waiting in the queue at once — a turn's own cue and the next turn's warning, where the two
 * turns are between fifty and seventy metres apart — and they stop being true at different moments.
 * One deadline over the pair would either take back the live one with the dead one, or keep the
 * dead one for the sake of the live one; both are a wrong sentence in the runner's ear.
 */
data class SaidTurn(
    val cue: TurnCue,
    /**
     * The first ground along the course at which this sentence is no longer true — false *from*
     * here on, and true everywhere short of it.
     *
     * **This is the whole of the staleness rule, stated once for both moments**, and named from the
     * dead side so the boundary needs no second thought: the runner reaching this ground is what
     * kills the sentence, so one comparison, `here >= falseFromAlongMeters`, settles every case.
     * A turn cue is a sentence about ground the runner is arriving at, and it stops being true when
     * they arrive. Where that ground sits is the only thing that differs:
     *
     *  - A warning says the turn is [TURN_WARNING_METERS] ahead, so it is false **at the turn**.
     *    Not late — false, because heard there it would send the runner that distance beyond the
     *    turning they are standing on. The turn's own cue is the true sentence by then.
     *  - The turn's own cue says to turn here, and is merely *late* for a while afterwards. It is
     *    false at [TURN_CUE_LATE_METERS] past the turn, which is where its whole allowance for
     *    being late has been spent — the same number, on the same side, as the one that decides
     *    whether the cue was worth making in the first place.
     */
    val falseFromAlongMeters: Double,
)

/**
 * What the turns of a course have to say about one fix (#456).
 *
 * Two things and not one, because a fix is both an occasion to speak and an occasion to unsay: the
 * runner reaching a corner earns "Turn left." and is exactly what makes the "Turn left in 50
 * metres." still waiting in the queue wrong. Which of the waiting sentences that is is not decided
 * here — this reports where the runner is and what each new sentence is good until, and
 * [CourseAlerts] holds those against the tickets it has outstanding, because it is the only thing
 * that knows what has actually been said.
 */
data class TurnVoice(
    /**
     * Where the runner is along the course, or null on a fix the turns did not read — auto-paused,
     * too coarse to trust, or before the course was reached. Null says nothing has gone stale,
     * because nothing has been measured.
     */
    val alongMeters: Double?,
    /** What to say, in the order to say it, each with the ground it is false from. */
    val said: List<SaidTurn>,
) {
    companion object {
        /** A fix the turns did not read: nothing to say, and nothing measured to unsay by. */
        val NOTHING = TurnVoice(alongMeters = null, said = emptyList())
    }
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
 * **Nothing is said twice.** The cues are held in the order the course reaches them and a pointer
 * walks that list forwards and never back — so a runner wobbling either side of the trigger point is
 * told once, and an out-and-back that brings them over the same ground the other way is *forward*
 * along the line and reaches the turns beyond it, not the ones behind.
 *
 * **A cue about ground already covered is not said at all, and one that becomes about ground already
 * covered while it waits its turn in the queue is taken back** ([TURN_CUE_LATE_METERS],
 * [SaidTurn.falseFromAlongMeters]). Both ends of the same rule: a turn cue is a sentence about
 * ground the runner is arriving at, and it is worth nothing once they have gone past it. Each cue
 * carries its own deadline, because two of them can be waiting at once and stop being true at
 * different moments.
 *
 * **How far off the line the runner is is not asked.** A course drawn down the middle of a road, a
 * runner on the far side of a dual carriageway and a file traced off somebody else's Run all put
 * honest running tens of metres out ([OFF_COURSE_METERS]), and that runner needs the corner told to
 * them most of all. A runner who has genuinely left the course has their own sentence for it and is
 * not helped by a second rule here — and one added here would be a rule that *loses* turns, the
 * cues it kept quiet arriving stale by the time the runner was back near enough to be told.
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
                CueAt(
                    alongMeters = it.alongMeters - TURN_WARNING_METERS,
                    falseFromAlongMeters = it.alongMeters,
                    cue = TurnCue(it.direction, TurnCueMoment.AHEAD),
                ),
                CueAt(
                    alongMeters = it.alongMeters,
                    falseFromAlongMeters = it.alongMeters + TURN_CUE_LATE_METERS,
                    cue = TurnCue(it.direction, TurnCueMoment.AT_THE_TURN),
                ),
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

    /**
     * Take one fix, and say whatever the turns ahead of the runner have to say about it — with,
     * for each sentence, the ground past which it should be unsaid.
     *
     * **Staleness is reported, not acted on.** A cue is enqueued and not spoken (#53): it waits
     * behind whatever sentence is in flight, and fifty metres is some ten seconds, so a cue can
     * outlive the ground it is about while it waits. The queue drops nothing, so the producer takes
     * it back — and the producer is the thing holding the tickets, [CourseAlerts]. What this can
     * say, and all it can say, is where the runner is and what each sentence is good until.
     */
    fun onFix(fix: LocationFix, autoPaused: Boolean): TurnVoice {
        if (autoPaused || !SessionRecorder.isAccuracyAccepted(fix.accuracyMeters)) return TurnVoice.NOTHING
        val here = course.progressAt(fix.latitude, fix.longitude, progress)
        progress = here
        if (!here.hasReachedTheCourse) return TurnVoice.NOTHING

        // Reaching the course says nothing, however much of it is behind: a runner who joins a loop
        // at its halfway point has not missed the turns of its first half, they are running the
        // second half. Every cue behind them is stepped over without a word.
        if (!started) {
            started = true
            nextCue = cues.indexOfFirst { it.alongMeters > here.alongMeters }.takeIf { it >= 0 } ?: cues.size
            return TurnVoice.NOTHING
        }

        val said = mutableListOf<SaidTurn>()
        while (nextCue < cues.size && cues[nextCue].alongMeters <= here.alongMeters) {
            val cue = cues[nextCue++]
            if (here.alongMeters - cue.alongMeters < TURN_CUE_LATE_METERS) {
                said += SaidTurn(cue.cue, cue.falseFromAlongMeters)
            }
        }
        return TurnVoice(alongMeters = here.alongMeters, said = said)
    }

    /**
     * One sentence, the ground along the course that earns it, and the ground it dies at
     * ([SaidTurn.falseFromAlongMeters], where the rule is argued).
     */
    private class CueAt(
        val alongMeters: Double,
        val falseFromAlongMeters: Double,
        val cue: TurnCue,
    )
}
