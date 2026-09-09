package com.example.runningapp.routes

import com.example.runningapp.recording.LocationFix

/**
 * Everything one course has to say to the runner following it: that they have left it or come back
 * to it (#58), and which way it bends ahead of them (#456).
 *
 * One object over the two watches rather than two handed about separately, because they are one
 * course's worth of voice and everything outside this package treats them alike — the same queue,
 * the same priority, the same tag, and taken back together the moment the line goes out from under
 * them ([CourseAlerts], #377). A second thing to watch is then a change here and nowhere else.
 *
 * **One [CourseLine] between them**, built once and lent to both, so that the two are reading the
 * same course rather than two courses that happen to have been built from the same list. Where each
 * *reads* from on that line is its own business, and deliberately different — [CourseTurnWatch] says
 * why.
 *
 * **What is said first, when both have something to say.** The off-course alert. Both go out at
 * [com.example.runningapp.CuePriority.NAVIGATION] and the queue is first-in-first-out within a
 * level (#53), so this ordering is the whole of what decides it: a runner who has just been told
 * they are off the line needs that before they need the line's next corner, and one who has just
 * been told they are back on it wants that before being told to turn. Neither cuts the other off —
 * nothing in this app cuts off a sentence already being spoken.
 *
 * Pure — no clock, no Android, no speech. Both watches are scripted in their own tests, and what is
 * left here is the pairing.
 */
class CourseVoice private constructor(
    private val offCourse: OffCourseWatch,
    private val turns: CourseTurnWatch,
) {

    /** Take one fix, and say everything this course has to say about it — in the order to say it. */
    fun onFix(fix: LocationFix, nowMillis: Long, autoPaused: Boolean): List<CourseSaying> {
        val said = mutableListOf<CourseSaying>()
        offCourse.onFix(fix, nowMillis, autoPaused)?.let { said += it }
        said += turns.onFix(fix, autoPaused)
        return said
    }

    /**
     * The fixes have stopped keeping up with the runner — a manual Pause, or the end of the Run.
     *
     * Only the off-course watch has anything to let go of: what it drops is a *wait*, ten seconds
     * the runner has to spend off the line, and a Pause is time they did not spend running
     * ([OffCourseWatch.recordingBroke]). The turns count ground and not time, so a Pause takes
     * nothing from them: the runner starts again exactly as far along the course as they stopped,
     * with the same turns still ahead of them.
     */
    fun recordingBroke() {
        offCourse.recordingBroke()
    }

    companion object {
        /**
         * A voice for the course [points] describe, or null when they describe no ground to run —
         * an unrouted Run, an empty Route, a Route deleted from the library before the Run got
         * going. A Run with no course has nothing to be told about.
         *
         * [points] arrive in the order the Run is running them, reversed already where the runner
         * said they were setting off the other way round — the same list the live map is drawn
         * from. That order is nothing to the off-course watch and everything to the turns: a course
         * run the other way round turns right where it turned left ([courseTurnsOf]).
         */
        fun of(points: List<RoutePoint>): CourseVoice? = CourseLine.of(points)?.let { line ->
            CourseVoice(OffCourseWatch(line), CourseTurnWatch(line, courseTurnsOf(points)))
        }
    }
}
