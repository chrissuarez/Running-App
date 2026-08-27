package com.example.runningapp.routes

import com.example.runningapp.recording.LocationFix
import com.example.runningapp.recording.SessionRecorder

/**
 * How far off the line the runner has to be before the app says anything about it (#58).
 *
 * Fifty metres is a street away, not a wide pavement: a course drawn down the middle of a road, a
 * runner on the far side of a dual carriageway, and a GPX file traced off somebody else's Run all
 * put honest running tens of metres off the line, and none of that is a wrong turn. It is also
 * comfortably outside the thirty metres a fix is trusted to (#38), so a fix good enough to be read
 * at all cannot be this far out by its own error.
 */
const val OFF_COURSE_METERS = 50.0

/**
 * How near the line the runner has to come back before they are told they are back on it.
 *
 * Nearer than [OFF_COURSE_METERS], and deliberately: one number for both would have a runner
 * hovering either side of it told they are off and back and off again for the length of a street.
 * Thirty metres is the same distance that arms the alerts in the first place
 * ([hasReachedTheCourse][CourseProgress.hasReachedTheCourse]) — "near enough to be on the course"
 * is one fact and is worth one number.
 */
const val BACK_ON_COURSE_METERS = 30.0

/**
 * How long the runner has to stay past [OFF_COURSE_METERS] before it is spoken.
 *
 * A wrong turn takes a few seconds to become fifty metres of wrong turn, and ten seconds of it is
 * about the point a runner would rather be told than not. Shorter would speak over a bend cut wide,
 * a lap of a car park, a crossing taken on the other side. Longer and the alert arrives after the
 * turn is expensive to undo.
 *
 * There is no matching wait on the way back: a runner returning to the line already knows what they
 * did, and "back on course" late is a cue about nothing.
 */
const val OFF_COURSE_SUSTAINED_MS = 10_000L

/** One of the two things the app says about the course, and the sentence it says (#58). */
enum class CourseAlert(val spoken: String) {
    /** The runner has been [OFF_COURSE_METERS] off the line for [OFF_COURSE_SUSTAINED_MS]. */
    OFF_COURSE("Off course."),

    /** They have come back within [BACK_ON_COURSE_METERS] of it. */
    BACK_ON_COURSE("Back on course."),
}

/**
 * Watches a routed Run against its course and says the two things there are to say (#58).
 *
 * Pure — no clock, no Android, no speech — and scripted in
 * [com.example.runningapp.routes.OffCourseTest] with fixes written in metres. It holds the only
 * judgement in the app about whether the runner is still on the course, so that judgement is made
 * once, here, and not a second time by whoever speaks it.
 *
 * How far off the line the runner is is not measured here either: it is
 * [CourseProgress.metersFromCourse], read off the same [CourseLine] the live map reads for distance
 * remaining (#57). What is left to decide is *when that number is worth a sentence*, and the whole
 * of that decision is the three constants above plus two rules that are easier to state than to
 * measure:
 *
 * **Nothing is said until the runner has reached the course.** A Run starts at the front door, and
 * the walk or jog to the start of the course is not a wrong turn — it is the runner going to the
 * course, and every metre of it is off the line. [CourseProgress.hasReachedTheCourse] is the same
 * fact the projection already keeps for its own purposes: they have been within thirty metres of it
 * at least once. Before that this says nothing, however far out they are.
 *
 * **A fix that is not trusted is not heard.** Detection suspends while the fix is coarser than the
 * thirty-metre gate everything else reads through
 * ([SessionRecorder.isAccuracyAccepted], #38) and while the Run is auto-paused (#39). A suspended
 * fix is not measured against the course at all — a fix that could be a hundred metres out by its
 * own error would move the anchor the *next* fix is read from — and any wait in progress starts
 * again from the next fix that is heard. What has already been said stands: a runner told they are
 * off course, whose phone then loses the sky, is still off course, and will be told when they get
 * back.
 */
class OffCourseWatch(private val course: CourseLine) {

    /** Where the last heard fix landed on the course — the anchor the next one is read from. */
    private var progress: CourseProgress? = null

    /** When the runner first went past [OFF_COURSE_METERS], or null when they are not out there. */
    private var strayingSinceMs: Long? = null

    /** Whether [CourseAlert.OFF_COURSE] has been said and not yet closed. */
    private var isOffCourse = false

    /**
     * Take one fix, and say what — if anything — the runner should be told because of it.
     *
     * [nowMillis] is the phone's clock rather than [LocationFix.timestampMs], for the same reason
     * the standstill in [SessionRecorder] uses it: the sustain is a wait the runner is living
     * through, and a fix's own timestamp is the satellites' account of when it was taken.
     */
    fun onFix(fix: LocationFix, nowMillis: Long, autoPaused: Boolean): CourseAlert? {
        if (autoPaused || !SessionRecorder.isAccuracyAccepted(fix.accuracyMeters)) {
            strayingSinceMs = null
            return null
        }
        val here = course.progressAt(fix.latitude, fix.longitude, progress)
        progress = here
        if (!here.hasReachedTheCourse) return null

        if (isOffCourse) {
            if (here.metersFromCourse > BACK_ON_COURSE_METERS) return null
            isOffCourse = false
            strayingSinceMs = null
            return CourseAlert.BACK_ON_COURSE
        }

        if (here.metersFromCourse <= OFF_COURSE_METERS) {
            strayingSinceMs = null
            return null
        }
        val since = strayingSinceMs ?: nowMillis
        strayingSinceMs = since
        if (nowMillis - since < OFF_COURSE_SUSTAINED_MS) return null
        isOffCourse = true
        strayingSinceMs = null
        return CourseAlert.OFF_COURSE
    }

    companion object {
        /**
         * A watch over the course [points] describe, or null when they describe no ground to run —
         * an unrouted Run, an empty Route, a Route deleted from the library before the Run got
         * going. A Run with no course cannot leave it.
         *
         * [points] arrive in the order the Run is running them, reversed already where the runner
         * said they were setting off the other way round — the same list the live map is drawn
         * from. Which way round the course is turned changes nothing here: how far off a line you
         * are is the same measurement in either direction.
         */
        fun of(points: List<RoutePoint>): OffCourseWatch? = CourseLine.of(points)?.let(::OffCourseWatch)
    }
}
