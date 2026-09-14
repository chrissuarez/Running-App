package com.example.runningapp

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
 * The Run's side of the cue queue: every cue the Run enqueues, kept on the Run's own books
 * ([OutstandingCues]) so that the Run can take back what has not been said (#53, #220, #479).
 *
 * The one place the books and the queue are kept in step. Every way a Run speaks comes through
 * here — the Run's own effects, the recorder's Splits, the UI's target-reached cue, and the
 * course's cues ([QueuedCourseCues]) — so every way a cue is taken back is taken off the books
 * before it reaches the queue, and nothing reaches the queue that the books do not know about.
 *
 * [hold] is read at every call, not once: the service takes its hold on the queue when a Run starts
 * and lets it go when the service goes, and a cue with no hold to go through is a cue nobody can
 * hear — so it is not enqueued, and not recorded.
 *
 * The lock order is the caller's, then [outstanding]'s, then the queue's — the order
 * [OutstandingCues.record] documents — and nothing here reaches back up.
 */
class RunCueQueue(
    private val outstanding: OutstandingCues,
    private val hold: () -> CueHold?,
) {

    /** Say something, in its turn among everything else waiting, under [tag] if it has a name. */
    fun enqueue(text: String, priority: CuePriority, tag: CueTag? = null): Long? {
        val queue = hold() ?: return null
        // Enqueued and recorded as one act, so the end of a Run cannot land between the two and
        // leave the cue outstanding with nothing left to take it back (#220).
        return outstanding.record(tag) { queue.enqueue(text, priority) }
    }

    /**
     * Take back the cues under [tag] that have not been spoken: whatever they were going to say is
     * no longer true (#208, #377).
     */
    fun takeBack(tag: CueTag) {
        // In one act, for the reason [AudioCueManager.withdrawAll] gives: taken back one at a time,
        // the engine can finish its sentence between two of them and hand the next out before its
        // own withdrawal reaches it.
        withdraw(outstanding.takeBack(tag))
    }

    /**
     * Take back these cues by their tickets, and no others (#456) — by name when everything under a
     * name has stopped being true together, and by ticket when only some of it has.
     */
    fun takeBack(tickets: List<Long>) {
        withdraw(outstanding.takeBackTickets(tickets))
    }

    /**
     * Take back every cue of the Run that has just ended (#220). A cue still waiting its turn
     * belongs to a Run that is over.
     *
     * The books are held across the withdrawal: a cue recorded between the snapshot and the
     * withdrawal would otherwise be left behind entirely ([OutstandingCues.takeBackAll]).
     */
    fun takeBackAll() {
        outstanding.takeBackAll { tickets -> hold()?.withdrawAll(tickets) }
    }

    private fun withdraw(tickets: List<Long>) {
        if (tickets.isEmpty()) return
        hold()?.withdrawAll(tickets)
    }
}
