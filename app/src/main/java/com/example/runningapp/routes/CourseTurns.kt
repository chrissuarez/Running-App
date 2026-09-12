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
     * too coarse to trust, before the course was reached, or out beyond it. Null says nothing has
     * gone stale *by ground*, because no ground has been measured.
     */
    val alongMeters: Double?,
    /** What to say, in the order to say it, each with the ground it is false from. */
    val said: List<SaidTurn>,
    /**
     * Everything the turns have waiting has stopped being true, whatever ground it was about.
     *
     * The second of the two ways a turn cue dies, and genuinely a different one: the first is the
     * runner passing the ground the cue names ([SaidTurn.falseFromAlongMeters]), and this is
     * everything ground can no longer settle.
     *
     * Two moments say it. The runner **leaving the course**: a sentence telling somebody which way
     * to turn on a line they are no longer running is not late, it is about nothing, and it cannot
     * be judged by ground because while they are out there no ground about them is known. And the
     * runner **arriving on the course** without having run the way to it — first reaching it,
     * rejoining it, or being carried past the far edge of the window ([CourseTurnWatch]'s
     * arrivals). Ground has jumped, so what is waiting was never run past; on a course that covers
     * the same ground twice the new reading can even be *earlier* along the line than the cue, and
     * ground would then keep a dead sentence alive for ever.
     */
    val takeBackWhatIsWaiting: Boolean = false,
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
 * **Silent until the runner has reached the course, and silent about the course behind them when
 * they do.** The jog from the front door is not a missed turn.
 * [CourseProgress.hasReachedTheCourse] is the same fact the off-course alerts arm on, and what
 * arriving on the course steps over is [stepOverTheTurnsBehind]'s to say.
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
 * **Honest wander off the line is not asked about; having left the course is.** A course drawn down
 * the middle of a road, a runner on the far side of a dual carriageway and a file traced off
 * somebody else's Run all put honest running tens of metres out, and that runner needs the corner
 * told to them most of all — so nothing here narrows what [OFF_COURSE_METERS] already calls being on
 * the course.
 *
 * Past it, the question is not whether to be helpful but whether anything is known. Where a fix
 * sits on the course is read from around the fix before it ([AHEAD_METERS]), so a runner far from
 * the line is placed at the edge of that window — and an edge can be a corner. A runner who sails
 * past a turning and keeps going is nearest, of everything the window covers, to the very turning
 * they missed, and the arithmetic then says they are standing on it. Read on, that is "Turn right."
 * spoken two hundred metres the wrong side of the corner, which is the exact sentence
 * [TURN_CUE_LATE_METERS] exists to prevent and cannot, because the ground is wrong rather than the
 * cue. So while the runner is out there this says nothing and trusts nothing.
 *
 * **A jump past the window's far edge is the same silence, and the same re-anchoring.** A Pause
 * spent in a car, a tunnel, a phone that lost the sky: the next fix can land further along the
 * course than the window around the last one reaches, and then the nearest place the window knows of
 * is its own far edge ([CourseProgress.heldBackByTheWindow]). An edge can be a corner, and thirty
 * metres round a turning is near enough to that corner to read as standing on it — near enough, too,
 * that [OFF_COURSE_METERS] calls it honest wander and lets the cue through, which is the one way a
 * corner already turned gets announced. So a reading the window held back is not judged as a place
 * at all; it is read against the whole course, exactly as a rejoin is.
 *
 * **Coming back is a re-anchoring, and it is silent.** Read against the whole course, the way
 * [OffCourseWatch] reads it at the same moment and for the same reason — a runner rejoins wherever
 * the streets let them, which can be past the end of the window the last fix opened. Where they
 * rejoin is where they are, and every cue behind it is stepped over without a word: a runner who
 * left at the first corner and came back at the last has not turned the corners in between and must
 * not be told about them. It costs the corners of the stretch they were away from, which are corners
 * they did not run.
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
                    turnAlongMeters = it.alongMeters,
                    cue = TurnCue(it.direction, TurnCueMoment.AHEAD),
                ),
                CueAt(
                    alongMeters = it.alongMeters,
                    falseFromAlongMeters = it.alongMeters + TURN_CUE_LATE_METERS,
                    turnAlongMeters = it.alongMeters,
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
     * Whether the runner is far enough off the line that where they are *on* it cannot be believed.
     *
     * [OFF_COURSE_METERS] is the app's own line between honest wander and having left the course,
     * and it is reused here rather than restated so that this watch and [OffCourseWatch] never
     * disagree about which of the two a runner is doing. Unlike that watch there is no wait to live
     * through first: the ten seconds are there so a blip cannot make the app *say* something wrong,
     * and going quiet on a blip says nothing at all.
     */
    private var strayed = false

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

        if (strayed) {
            // Against the whole course, not against the stretch of it the runner was last near —
            // the argument [OffCourseWatch.onFix] makes at this same moment. The window around
            // where they were cannot reach where they have got to, so asking it would answer with
            // its own edge for the rest of the Run.
            //
            // An out-and-back can read as its outward half here, which the window exists to
            // prevent; that is the price of having nothing better, and it is the price the other
            // watch already pays. Nothing is claimed while they are away, and the moment they are
            // back the whole-line reading *is* where they are, so it becomes the anchor again.
            val rejoined = course.progressAt(fix.latitude, fix.longitude, previous = null)
            if (rejoined.metersFromCourse > BACK_ON_COURSE_METERS) return TurnVoice.NOTHING
            strayed = false
            return arriveAt(rejoined)
        }

        val here = course.progressAt(fix.latitude, fix.longitude, progress)
        if (!here.hasReachedTheCourse) {
            progress = here
            return TurnVoice.NOTHING
        }
        if (here.heldBackByTheWindow) {
            // The runner has been carried more than the window is long since the last fix, so the
            // piece of course they are on was never looked at and what came back is the window's own
            // far edge ([CourseProgress.heldBackByTheWindow]). An edge can be a corner, and thirty
            // metres round a turning is near enough to that corner to read as standing on it — near
            // enough, too, that the off-course branch below would call it honest wander and let the
            // cue through. So it is not judged as a place at all: the whole line is read instead,
            // the same reading a rejoin takes and for the same reason, and it is the same event —
            // the runner is suddenly somewhere on the course without having run the way to it, so
            // every corner behind them is stepped over without a word.
            val where = course.progressAt(fix.latitude, fix.longitude, previous = null)
            if (where.metersFromCourse > OFF_COURSE_METERS) {
                strayed = true
                return TurnVoice(alongMeters = null, said = emptyList(), takeBackWhatIsWaiting = true)
            }
            return arriveAt(where)
        }
        if (here.metersFromCourse > OFF_COURSE_METERS) {
            // The anchor is left where it was. It is the last place the runner was actually seen on
            // the course, and a place read from out here is not a place.
            //
            // And whatever was still waiting to be said goes with them. A runner who misses a
            // corner and runs on is the very case where a "Turn right." is sitting in the queue
            // behind a longer sentence, and it cannot be judged by ground from here — no ground
            // about them is known while they are out there, which is the whole reason this branch
            // exists. Leaving it would speak it minutes later, off the course, about a corner they
            // did not take.
            strayed = true
            return TurnVoice(alongMeters = null, said = emptyList(), takeBackWhatIsWaiting = true)
        }
        if (!started) return arriveAt(here)
        progress = here
        return whatIsDueAt(here)
    }

    /**
     * Take a reading as where the runner now is, and say only what is in front of them.
     *
     * The one path for all three arrivals — first reaching the course, rejoining it after straying,
     * and a jump past the window's far edge — because they are the one event: the runner is
     * somewhere on the course without having run the way to it from where they were last seen. Each
     * of the three hands in a reading taken against the *whole* line, which is the only reading that
     * can place a runner who did not walk there. See [stepOverTheTurnsBehind] for what that costs
     * and why.
     *
     * **And whatever was still waiting to be said goes back, whatever ground it named.** An arrival
     * says the runner did not run the ground between where they were last seen and here, so nothing
     * waiting can be judged by ground any more: on a course that covers the same ground twice, the
     * whole-line reading can land *earlier* along the line than a cue already spoken about the lap
     * they have just jumped past, and a cue behind ground that has gone backwards is never reached
     * — it would sit in the queue and be spoken minutes later about a corner they have long since
     * turned. Cheap on the other two arrivals and true on all three: the first reach has nothing
     * waiting yet, and a rejoin emptied the queue on the fix that reported the leaving.
     *
     * The pointer is a separate matter and is not rewound — [stepOverTheTurnsBehind] only ever goes
     * forwards — because what has been said once is not owed again. This is about the sentence
     * already handed over and still queued, which no pointer governs.
     */
    private fun arriveAt(here: CourseProgress): TurnVoice {
        progress = here
        started = true
        stepOverTheTurnsBehind(here.alongMeters)
        return whatIsDueAt(here).copy(takeBackWhatIsWaiting = true)
    }

    /**
     * Everything the cues have to say about the runner being here, and nothing about anywhere else.
     *
     * The pointer walks forwards over every cue whose ground has been reached, and each of those is
     * said unless the runner is already [TURN_CUE_LATE_METERS] past it — a cue about ground behind
     * them is not a late cue, it is a wrong one.
     */
    private fun whatIsDueAt(here: CourseProgress): TurnVoice {
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
     * Put the pointer past every cue about a **turn** the runner has already reached, because they
     * have arrived on the course here and did not run the ground behind them.
     *
     * The one rule for all three arrivals — first reaching the course, rejoining it after straying,
     * and a jump past the window's far edge ([arriveAt]) — because they are the same event: the
     * runner is suddenly somewhere on the course without having run the way to it. A runner who
     * joins a loop at its halfway point has not missed the turns of its first half, and one who left
     * at the first corner and came back at the last has not turned the corners in between. None of
     * them is told about them.
     *
     * **What a cue is about, not where it is said.** A warning is triggered fifty metres before its
     * turn and is about that turn, so a runner who arrives between the two — past the trigger, short
     * of the corner — has a corner genuinely in front of them and is told about it. Stepping over by
     * where the cue is *triggered* would swallow that warning and leave the corner announced only
     * once they were standing on it, which is the thing this feature exists to stop.
     *
     * **Forwards only, never back.** A course can cover the same ground twice — a two-lap loop —
     * and the whole-line reading deliberately prefers the *earlier* of two equally near places, so a
     * rejoin on the second lap can be reported as the first. Keeping the pointer where it is stops
     * the lap's turns being announced a second time; the cost is that the rest of a lap read as the
     * wrong one stays silent, which is the way round to be wrong.
     */
    private fun stepOverTheTurnsBehind(alongMeters: Double) {
        val ahead = cues.indexOfFirst { it.turnAlongMeters > alongMeters }.takeIf { it >= 0 }
            ?: cues.size
        nextCue = maxOf(nextCue, ahead)
    }

    /**
     * One sentence, the ground along the course that earns it, and the ground it dies at
     * ([SaidTurn.falseFromAlongMeters], where the rule is argued).
     */
    private class CueAt(
        val alongMeters: Double,
        val falseFromAlongMeters: Double,
        /**
         * The turn this sentence is about, as ground along the course — its own ground for the cue
         * said at the turn, and fifty metres on for the warning said before it.
         *
         * What a cue is *about* is not where it is said, and arriving on the course is the one
         * moment that tells them apart: a warning triggered behind the runner can still be about a
         * corner in front of them. See [stepOverTheTurnsBehind].
         */
        val turnAlongMeters: Double,
        val cue: TurnCue,
    )
}
