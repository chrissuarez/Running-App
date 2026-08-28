package com.example.runningapp.segments

import android.util.Log
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.measureTrack

/**
 * Everything the shaping below reads and writes — the database as this pass needs it.
 *
 * An interface for [SegmentTimingStore]'s reason: these four operations are one thing, and they are
 * the whole of what a test has to stand up to check the ordering and the eligibility on a laptop.
 */
interface RunShapeStore {
    suspend fun run(sessionId: Long): RunnerSession?
    /** The finished Runs nobody has taken the shape of — every debt, oldest first. */
    suspend fun runsMissingShapes(): List<Long>
    suspend fun track(sessionId: Long): List<TrackPoint>

    /**
     * Writes one Run's shape down — **unless the Run stopped being the one it was measured from**.
     *
     * The check and the write are one operation because they have to be: a re-read on this side of
     * the database only narrows the window, it does not close it. Implementations do the reading
     * inside the same transaction as the write, the way a Run's scoring does
     * ([com.example.runningapp.data.SessionRepository] and #210).
     *
     * Returns false where the write was abandoned, which leaves the Run owing a shape — the debt
     * being the absence of a row, and the safe thing to be left holding.
     */
    suspend fun putShapeUnlessTheRunMoved(sessionId: Long, shape: RunShape?, measuredAs: RunnerSession): Boolean
}

/**
 * Whether two readings of the same Run would have the same shape taken from them — everything the
 * shaping decides by, and nothing else (#73).
 *
 * `contestsAs`'s rule and for its reason: a Run's feel, its note and its Effort Score can all be
 * written while its track is being measured, and none of them can move a waypoint, so none of them
 * is a reason to throw a measurement away. What is here is the three things
 * [mayBeMatchedToOtherRuns] asks — a Run finishing, being marked a Walk, becoming a treadmill Run —
 * because each of those changes the shape the Run should hold from a route to nothing, or back.
 *
 * Deferred to [holdsEffortsAs] rather than written out again, the way [mayBeMatchedToOtherRuns]
 * defers to [mayHoldSegmentEfforts]: the two ask the same three columns because they are gated on
 * the same eligibility, and two copies of that would be free to drift apart at the next change to
 * either.
 */
fun RunnerSession.shapesAs(other: RunnerSession): Boolean = holdsEffortsAs(other)

/**
 * Taking the shape of Runs, so they can recognise each other (#73).
 *
 * One door, three occasions: a Run that has just finished, a Run whose runner has changed their word
 * about it, and the whole of history at the first launch after this shipped. All three are the same
 * measurement, so they are the same code and cannot drift into disagreeing about what a route is.
 *
 * Nothing here groups anything. A group is worked out on read, off the shapes this leaves behind
 * ([com.example.runningapp.ui.matchedRunsUi]) — which is what makes the grouping need no mending
 * when a Run is deleted, and makes running this pass twice cost arithmetic and nothing else.
 *
 * **A shape is written even when there is none to take**, as a row saying so
 * ([com.example.runningapp.data.RunShapeRow]). That empty write is how a Run marked a Walk leaves
 * the groups it was in, and it is why a Walk is not re-read at every launch for the rest of its life.
 */
class RunShaping(private val store: RunShapeStore) {

    /**
     * Takes one Run's shape and writes it down — what a Run gets when it finishes, and again
     * whenever the runner's word about it changes.
     *
     * The row is read here rather than handed in, [SegmentTiming.time]'s rule and for its reason:
     * this can be seconds of arithmetic on a long track and the runner is free the whole time, so
     * what the Run *is* has to be asked at the moment its shape is written rather than before it.
     *
     * **And the write is abandoned where the Run moved under it**, because "at the moment its shape
     * is written" is the whole of the rule and a read taken before seconds of arithmetic does not
     * keep it. A runner who marks a Walk in that window flips the answer under the measurement, and
     * the write that followed would bank a shape for a Walk — or, unmarking, bank no shape for a
     * Run. Either way a row now exists, so [payWhatIsOwed] never looks at that Run again and the
     * mistake is permanent. A second read on this side of the database only narrows that window, so
     * the check travels *into* the write instead ([RunShapeStore.putShapeUnlessTheRunMoved]).
     *
     * Abandoning is safe and is the point: the Run is left owing a shape, and the mark that
     * overtook it takes that shape itself — every door into this deletes the row before it changes
     * what the Run is, and the launch pass sweeps up whatever is still owed. A shape banked from a
     * Run nobody has is what could not be undone.
     *
     * A Run that is gone is written as nothing at all — there is no row left to hang a shape on.
     */
    suspend fun shapeRun(sessionId: Long) {
        val run = store.run(sessionId) ?: return
        val shape = if (run.mayBeMatchedToOtherRuns()) {
            runShapeOf(measureTrack(store.track(sessionId)))
        } else {
            null
        }
        if (!store.putShapeUnlessTheRunMoved(sessionId, shape, measuredAs = run)) {
            Log.d(TAG, "Run $sessionId changed while its shape was being taken; leaving it owing one")
            return
        }
        Log.d(TAG, "Run $sessionId " + (shape?.let { "covers %.0f m".format(it.distanceMeters) } ?: "holds no shape"))
    }

    /**
     * Takes the shape of every Run that has never had one — the launch pass, and the backfill over
     * all of history (#73).
     *
     * On the launch this shipped that is every Run the runner has ever recorded, which is the point:
     * matched runs are worth nothing to somebody whose fourteenth lap of the park is the first one
     * the app has ever looked at. On every launch afterwards it reads an empty list and returns.
     *
     * Each Run's row is written as it is measured, so a pass cut short by a process being reclaimed
     * keeps everything it has already done and the next launch takes up the rest.
     */
    suspend fun payWhatIsOwed() {
        val owed = store.runsMissingShapes()
        owed.forEach { shapeRun(it) }
        if (owed.isNotEmpty()) Log.d(TAG, "Took the shape of ${owed.size} run(s)")
    }

    private companion object {
        const val TAG = "MatchedRuns"
    }
}
