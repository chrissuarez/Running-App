package com.example.runningapp

import com.example.runningapp.run.CueTag

/**
 * The queue tickets for the cues a Run has enqueued, so that the Run can take back the ones that
 * have not been spoken yet (#53, #220).
 *
 * The cue queue never drops a cue: the one way a cue leaves it unspoken is its producer asking for
 * it back. Every cue in the app is enqueued by the service on a Run's behalf — the Run's own
 * effects, the recorder's Splits, the UI's target-reached — so the service is that producer, and
 * this is the whole of what it has to remember to act like one.
 *
 * Two shapes of taking back, both the producer's:
 *
 * - **By name** ([takeBack]), for a cue the Run has changed its mind about mid-Run — the halfway
 *   turnaround when the Run skips into its cool-down (#208).
 * - **All of them** ([takeBackAll]), at the end of the Run, because a cue that has not been spoken
 *   by then is an instruction for a Run that is over (#220).
 *
 * A ticket for a cue that has already been spoken is inert when it is handed back, so nothing here
 * tracks what has gone out — which is why every ticket of a Run is kept until the Run ends. A long
 * Run leaves a few hundred numbers behind, and the end of it clears them.
 *
 * Reached from every thread that enqueues — the Run's, the recorder's, the UI's — and from the STOP
 * path, which is the binder's, so all of the state is behind this instance's lock.
 */
class OutstandingCues {

    private val lock = Any()

    /** Every ticket issued during this Run, oldest first, spoken or not. */
    private val tickets = LinkedHashSet<Long>()

    /** The tickets of the cues the Run may ask for back by name. */
    private val byTag = mutableMapOf<CueTag, Long>()

    /**
     * Enqueue a cue and remember it, under [tag] if the Run named it, as one act — [enqueue] is
     * called from under this instance's lock and the ticket it hands back is outstanding before any
     * other thread can look. Returns that ticket, or null when [enqueue] made no promise to keep.
     *
     * Enqueueing and recording have to be one act because they race the end of the Run. Enqueueing
     * first and recording after leaves a window in which [takeBackAll] runs between the two, and the
     * cue is then recorded against a Run that is already over, with no later pass to take it back —
     * it would be spoken after the Run ended, which is the whole of what #220 is about. The ends of
     * a Run that reach here are natural ones (the cool-down running out), which have no second
     * inline sweep behind them.
     *
     * The lock order is this instance's lock, then the queue's. It is the order [takeBack] and
     * [takeBackAll] and their callers already take, and nothing in the queue reaches back here, so
     * holding across [enqueue] adds no way to deadlock.
     */
    fun record(tag: CueTag? = null, enqueue: () -> Long?): Long? {
        synchronized(lock) {
            val ticket = enqueue() ?: return null
            tickets += ticket
            if (tag != null) byTag[tag] = ticket
            return ticket
        }
    }

    /**
     * The ticket for the cue named [tag], which is now the caller's to withdraw — or null when
     * there is no such cue outstanding, which is most of the time.
     */
    fun takeBack(tag: CueTag): Long? {
        synchronized(lock) {
            val ticket = byTag.remove(tag) ?: return null
            tickets.remove(ticket)
            return ticket
        }
    }

    /**
     * Hand every ticket this Run issued to [withdraw], in the order the cues were enqueued, and
     * leave nothing outstanding — so the next Run starts with its own cues and only its own.
     *
     * [withdraw] is called from under this instance's lock, for the same reason [record] enqueues
     * from under it: a cue recorded between the snapshot and the withdrawal would not be in the
     * list, and nothing would come back for it. Holding across both makes the end of a Run one act
     * against everything a Run can still be enqueueing.
     *
     * A cue enqueued strictly after this has run is not this Run's and is not taken back — the app
     * speaks outside a Run too, and that is what makes the desk-test cue button work.
     */
    fun takeBackAll(withdraw: (List<Long>) -> Unit) {
        synchronized(lock) {
            val all = tickets.toList()
            tickets.clear()
            byTag.clear()
            withdraw(all)
        }
    }
}
