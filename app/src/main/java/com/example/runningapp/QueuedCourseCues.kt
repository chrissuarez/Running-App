package com.example.runningapp

import android.util.Log
import com.example.runningapp.routes.CourseCueQueue
import com.example.runningapp.routes.CourseSaying
import com.example.runningapp.run.CueTag

/**
 * One hold on the cue queue: say something in its turn, and take back what has not been said yet
 * (#53). [AudioCueManager.Lease] is the real one; a test holds a queue that never speaks.
 */
interface CueHold {
    /** Enqueue [text] at [priority], and hand back its ticket — null when there was no queue to join. */
    fun enqueue(text: String, priority: CuePriority): Long?

    /** Take these cues back, in one act. Inert for a ticket already spoken or already taken back. */
    fun withdrawAll(tickets: Collection<Long>)
}

/**
 * The course's voice, wired to the Run's cue queue (#58, #377, #456, #479).
 *
 * Everything the course says goes in under one name, [CueTag.COURSE], at one priority,
 * [CuePriority.NAVIGATION], and through the Run's own [outstanding] bookkeeping — so the end of the
 * Run takes a course cue back like any other (#220), and a withdrawal by name or by ticket is taken
 * off those books before it reaches the queue. Those three facts are the whole of this class, and
 * they are the ones that used to be wired by hand in the service with nothing to test them.
 *
 * [hold] is read at every call, not once: the service takes its hold on the queue when a Run
 * starts and lets it go when the service goes, and a course cue with no hold to go through is a cue
 * nobody can hear — so it is not enqueued, and not recorded.
 *
 * The lock order is the caller's, then [outstanding]'s, then the queue's — the order
 * [OutstandingCues.record] documents — and nothing here reaches back up.
 */
class QueuedCourseCues(
    private val outstanding: OutstandingCues,
    private val hold: () -> CueHold?,
) : CourseCueQueue {

    override fun enqueue(saying: CourseSaying): Long? {
        val queue = hold() ?: return null
        Log.d(HrForegroundService.TAG, "Course cue: ${saying.spoken}")
        // Enqueued and recorded as one act, so the end of a Run cannot land between the two (#220).
        return outstanding.record(CueTag.COURSE) { queue.enqueue(saying.spoken, CuePriority.NAVIGATION) }
    }

    override fun takeBackAll() {
        val tickets = outstanding.takeBack(CueTag.COURSE)
        if (tickets.isEmpty()) return
        // In one act, for the reason [AudioCueManager.withdrawAll] gives: taken back one at a time,
        // the engine can finish its sentence between two of them and hand the next out before its
        // own withdrawal reaches it.
        hold()?.withdrawAll(tickets)
    }

    override fun takeBack(tickets: List<Long>) {
        val taken = outstanding.takeBackTickets(tickets)
        if (taken.isEmpty()) return
        hold()?.withdrawAll(taken)
    }
}
