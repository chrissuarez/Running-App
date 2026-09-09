package com.example.runningapp.routes

import com.example.runningapp.recording.LocationFix
import kotlinx.coroutines.flow.Flow

/**
 * The app's voice about the course for the length of one Run: which course is being watched, what
 * it has to say about each fix, and what happens to a sentence that has not been said yet when the
 * course goes out from under it (#58, #377, #456).
 *
 * The judgement itself is [CourseVoice]'s and stays there. What is here is the pairing of that
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
    /**
     * Enqueue this sentence, in its turn — tagged, so that [withdraw] can name it again — and hand
     * back the queue's ticket for it, or null if it was not enqueued at all.
     *
     * The ticket is what lets one waiting sentence be taken back and another left alone
     * ([withdrawCues]).
     */
    private val speak: (CourseSaying) -> Long?,
    /** Take back everything this Run's course had waiting to be said, of every kind. */
    private val withdraw: () -> Unit,
    /**
     * Take back exactly these cues, by the tickets [speak] handed back, and nothing else (#456).
     *
     * By ticket rather than by name, and separate from [withdraw] on purpose: this fires while the
     * course still stands and while other sentences about it are still true. A runner who has
     * reached a corner has not stopped being off the line by reaching it, and one stale turn cue
     * does not make the next turn's warning waiting behind it stale.
     */
    private val withdrawCues: (List<Long>) -> Unit,
    /**
     * The clock the ten-second wait is lived through — the phone's, for the reason
     * [OffCourseWatch.onFix] gives.
     */
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    /** The course being watched, or null for a Run following none — and for a Route deleted. */
    private var watch: CourseVoice? = null

    /**
     * The cues of this course that carry a deadline, each with the ground it stops being true at.
     *
     * Kept here and not in the watch that judged them, because taking a cue back needs its queue
     * ticket and this is the only thing that has ever held one. A ticket for a cue already spoken
     * is inert when it is handed back, so nothing here has to know what has gone out — an entry
     * simply leaves when the ground passes it.
     *
     * Emptied whenever the course changes, because [withdraw] has just taken every one of them back
     * wholesale (#377) and what replaces them belongs to a different line.
     */
    private val waiting = mutableListOf<WaitingCue>()

    /** One enqueued cue, by its queue ticket, and the ground past which it is not worth saying. */
    private class WaitingCue(val ticket: Long, val trueUntilAlongMeters: Double)

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
    suspend fun follow(courses: Flow<CourseVoice?>) {
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
        waiting.clear()
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
    private fun watchInstead(mine: Long, next: CourseVoice?) {
        synchronized(lock) {
            if (mine != watching) return
            withdraw()
            waiting.clear()
            watch = next
        }
    }

    /**
     * Take one fix, and enqueue everything the course being watched has to say about it.
     *
     * All of it, in the order [CourseVoice] hands it over: one fix can be both the moment the
     * runner comes back onto the line and the moment the corner fifty metres ahead is worth a word,
     * and enqueueing only one of the two would be picking which of them is true.
     *
     * **What is stale goes back before what is new goes in.** A cue is enqueued and not spoken, and
     * the queue drops nothing (#53) — so a turn cue can outlive the ground it is about while it
     * waits behind a sentence already in flight. Which cues those are is [CourseTurnWatch]'s
     * judgement and stays there; what is here is the pairing of that judgement with the queue, the
     * same as everything else in this class. Taking back first is what stops the withdrawal
     * swallowing the very sentence that replaces what it took.
     */
    fun onFix(fix: LocationFix, autoPaused: Boolean) {
        synchronized(lock) {
            val speech = watch?.onFix(fix, nowMillis(), autoPaused) ?: return
            speech.alongMeters?.let(::takeBackWhatIsStaleAt)
            speech.said.forEach { utterance ->
                val ticket = speak(utterance.saying)
                if (ticket != null && utterance.trueUntilAlongMeters != null) {
                    waiting += WaitingCue(ticket, utterance.trueUntilAlongMeters)
                }
            }
        }
    }

    /**
     * Take back every cue still waiting that the runner is now past the ground of — one by one, and
     * leaving the rest exactly where they are.
     *
     * **One at a time is the whole point.** Two turn cues can be in the queue together — a turn's
     * own cue and the next turn's warning, where the two turns are between fifty and seventy metres
     * apart — and they stop being true at different moments. Taken back as a batch, either the live
     * one goes with the dead one or the dead one stays for the sake of the live one, and each is a
     * wrong sentence in the runner's ear. So the ticket, not the name, is what is handed back.
     *
     * Under this class's lock, from [onFix], so the list is never read while a cue is being added
     * to it.
     */
    private fun takeBackWhatIsStaleAt(alongMeters: Double) {
        val stale = waiting.filter { alongMeters > it.trueUntilAlongMeters }
        if (stale.isEmpty()) return
        waiting -= stale.toSet()
        withdrawCues(stale.map { it.ticket })
    }

    /**
     * The fixes have stopped keeping up with the runner — a manual Pause, or the end of the Run.
     * See [CourseVoice.recordingBroke]; only the wait is let go of.
     */
    fun recordingBroke() {
        synchronized(lock) { watch?.recordingBroke() }
    }
}
