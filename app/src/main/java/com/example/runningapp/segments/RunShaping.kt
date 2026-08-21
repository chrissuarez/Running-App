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
    suspend fun putShape(sessionId: Long, shape: RunShape?)
}

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
     * **And asked again after the measurement**, because "at the moment its shape is written" is the
     * whole of the rule and one read before seconds of arithmetic does not keep it. A runner who
     * marks a Walk while this is measuring flips the answer under it, and the write that followed
     * would bank a shape for a Walk — or, unmarking, bank no shape for a Run. Either way the row now
     * exists, so [payWhatIsOwed] never looks at that Run again and the mistake is permanent. Asked
     * twice, the write is the answer the Run holds *now*; asked again if it moved, because the
     * second answer is only worth writing if it is still true.
     *
     * A Run that is gone is written as nothing at all — there is no row left to hang a shape on.
     */
    suspend fun shapeRun(sessionId: Long) {
        while (true) {
            val before = store.run(sessionId) ?: return
            val eligible = before.mayBeMatchedToOtherRuns()
            val shape = if (eligible) runShapeOf(measureTrack(store.track(sessionId))) else null
            val after = store.run(sessionId) ?: return
            if (after.mayBeMatchedToOtherRuns() != eligible) continue
            store.putShape(sessionId, shape)
            Log.d(TAG, "Run $sessionId " + (shape?.let { "covers %.0f m".format(it.distanceMeters) } ?: "holds no shape"))
            return
        }
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
