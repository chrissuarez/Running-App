package com.example.runningapp.routes

import com.example.runningapp.recording.LocationFix
import kotlinx.coroutines.flow.Flow

/**
 * The app's voice about the course for the length of one Run: which course is being watched, what
 * it has to say about each fix, and what happens to a sentence that has not been said yet when the
 * course goes out from under it (#58, #377).
 *
 * The judgement itself is [OffCourseWatch]'s and stays there. What is here is the pairing of that
 * judgement with the queue: a course alert is enqueued rather than spoken, and the queue never cuts
 * off the sentence already in flight (#53), so an alert can wait a whole split announcement before
 * it is heard. [courseToWatchFlow] can hand over a different course — or no course at all, the
 * Route deleted from the library — inside that wait. Then the line the alert is about is one the
 * live map has already stopped drawing, and saying it anyway tells the runner about a course the app
 * no longer holds.
 *
 * So the two acts are held together here: the course a cue was made about, and the cue. Whenever the
 * course changes, whatever it made and nobody has heard is taken back first.
 *
 * **One lock over both.** A fix is read on the location callback's thread and the course arrives on
 * the collector's, and a cue that is enqueued after the withdrawal has swept past it is exactly the
 * cue this exists to stop — reading the course, asking it about the fix and enqueueing what it says
 * has to be one act against replacing the course and taking its cues back. Both are a few
 * microseconds of arithmetic.
 *
 * A lock rather than the Run's single thread (ADR 0002) because none of this is the Run's: the
 * rulebook has never heard of a GPS fix, and what is held together here is a reading of the phone
 * against a row of the library. The lock order is this instance's, then the cue bookkeeping's, then
 * the queue's — [speak] and [withdraw] both go that way and neither reaches back in here, so
 * holding across them adds no way to deadlock.
 *
 * **And a number for which watching is current**, because cancelling a collection is a request and
 * not an act: a collector already inside an emission when [stop] is called runs it to the end, and
 * would put a course back that has just been let go of. A collection writes nothing once its number
 * has moved on, so "this course has been stopped watching" is decided under the same lock that
 * replaces it rather than by whoever cancels first.
 */
class CourseAlerts(
    /** Enqueue this sentence, in its turn — tagged, so that [withdraw] can name it again. */
    private val speak: (CourseAlert) -> Unit,
    /** Take back every course alert of this Run that has not been spoken. */
    private val withdraw: () -> Unit,
    /**
     * The clock the ten-second wait is lived through — the phone's, for the reason
     * [OffCourseWatch.onFix] gives.
     */
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    /** The course being watched, or null for a Run following none — and for a Route deleted. */
    private var watch: OffCourseWatch? = null

    /** Which watching is the current one. A collection with an older number writes nothing. */
    private var watching = 0L

    /**
     * Watch each course [courses] hands over, in turn, until the collection is cancelled.
     *
     * Every emission is a course that has genuinely changed shape ([courseToWatchFlow]), which is
     * exactly the moment an outstanding alert about the old shape stops being true.
     *
     * Beginning is itself a stop: this course is watched from nothing, so a course left behind by
     * whatever was being watched before has nothing waiting by the time the first fix is read.
     */
    suspend fun follow(courses: Flow<OffCourseWatch?>) {
        val mine = beginWatching()
        courses.collect { next -> watchInstead(mine, next) }
    }

    /**
     * Stop watching anything, and take back whatever the last course had waiting to be said.
     *
     * Every [follow] outstanding is stopped by this, whether or not its coroutine has noticed it has
     * been cancelled — that is what the number is for.
     */
    fun stop() {
        beginWatching()
    }

    /** Let go of the course being watched, and give out the number for whatever comes next. */
    private fun beginWatching(): Long = synchronized(lock) {
        withdraw()
        watch = null
        ++watching
    }

    /**
     * Watch [next] from now on, and take back what the course before it left waiting — unless
     * [mine] is a watching that has since been stopped, in which case this says nothing about the
     * course at all.
     *
     * Nothing is carried across: [next] has never heard of the runner, so a runner told they were
     * off the old line is not told they are back on this one, and an "Off course." withdrawn here
     * leaves no half-state behind — the state that made it went with the watch that made it.
     */
    private fun watchInstead(mine: Long, next: OffCourseWatch?) {
        synchronized(lock) {
            if (mine != watching) return
            withdraw()
            watch = next
        }
    }

    /** Take one fix, and enqueue whatever the course being watched has to say about it. */
    fun onFix(fix: LocationFix, autoPaused: Boolean) {
        synchronized(lock) {
            val alert = watch?.onFix(fix, nowMillis(), autoPaused) ?: return
            speak(alert)
        }
    }

    /**
     * The fixes have stopped keeping up with the runner — a manual Pause, or the end of the Run.
     * See [OffCourseWatch.recordingBroke]; only the wait is let go of.
     */
    fun recordingBroke() {
        synchronized(lock) { watch?.recordingBroke() }
    }
}
