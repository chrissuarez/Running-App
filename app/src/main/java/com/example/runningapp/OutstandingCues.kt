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

    /** Remember a cue this Run has just enqueued, under [tag] if the Run named it. */
    fun record(ticket: Long, tag: CueTag? = null) {
        synchronized(lock) {
            tickets += ticket
            if (tag != null) byTag[tag] = ticket
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
     * Every ticket this Run issued, in the order the cues were enqueued, and nothing is left
     * outstanding afterwards — so the next Run starts with its own cues and only its own.
     */
    fun takeBackAll(): List<Long> {
        synchronized(lock) {
            val all = tickets.toList()
            tickets.clear()
            byTag.clear()
            return all
        }
    }
}
