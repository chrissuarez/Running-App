package com.example.runningapp.segments

import android.util.Log
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.Segment
import com.example.runningapp.data.SegmentEffort
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.isFinished
import com.example.runningapp.data.isTreadmill
import com.example.runningapp.data.measureTrack
import com.example.runningapp.routes.RoutePolyline

/**
 * Everything the walk below reads and writes, in one place — the database as this pass needs it.
 *
 * An interface rather than a handful of lambdas because these ten operations are one thing: they are
 * what "Segments and the Runs they are timed against" looks like from here, and a caller that could
 * hand over nine of them would be a caller that could half-wire the pass. It is also the whole of
 * what a test has to stand up, which is what keeps the ordering and the eligibility below checkable
 * on a laptop.
 */
interface SegmentTimingStore {
    suspend fun segments(): List<Segment>
    suspend fun segment(segmentId: Long): Segment?
    /** The Segments history has never been walked against — every debt, oldest first. */
    suspend fun segmentsMissingHistory(): List<Segment>
    suspend fun runs(): List<RunnerSession>
    suspend fun run(sessionId: Long): RunnerSession?
    /** The finished Runs nobody has walked against the Segments — every debt, oldest first. */
    suspend fun runsMissingTiming(): List<Long>
    suspend fun track(sessionId: Long): List<TrackPoint>

    /**
     * Writes what one Run is worth at one Segment — **unless the Run stopped being the one that was
     * measured**.
     *
     * The check and the write are one operation because they have to be: a re-read on this side of
     * the database only narrows the window, it does not close it (#338, #343). Implementations do
     * the reading inside the same transaction as the write, the way a Run's shape is written
     * ([RunShapeStore.putShapeUnlessTheRunMoved]) and the way its scoring is (#210).
     *
     * [measuredAs] is the row [SegmentTiming.time] measured from, and null where it found no Run at
     * all. Null is a reading like any other and is checked like one: a Run that has since come back
     * is a Run this measurement knows nothing about.
     *
     * Returns false where the write was abandoned, which leaves both debts standing.
     */
    suspend fun replaceEffortsUnlessTheRunMoved(
        segmentId: Long,
        sessionId: Long,
        efforts: List<SegmentEffort>,
        measuredAs: RunnerSession?,
    ): Boolean

    suspend fun markSegmentTimed(segmentId: Long)
    suspend fun markRunTimed(sessionId: Long)
}

/**
 * Putting Runs and Segments to each other, and writing down what comes of it (#70).
 *
 * Two occasions, one answer. A Segment is born with its whole history behind it — the runner has
 * been running that hill for a year and the page says so the moment they name it — and every Run
 * that finishes afterwards is put to every Segment there is. Both are the same walk, done from
 * opposite ends, so they are the same code and cannot drift into disagreeing about what an effort is.
 *
 * The body is here rather than in the repository so the order and the eligibility can be checked on
 * a laptop, the bargain [com.example.runningapp.data.AfterRunRoutine] makes.
 *
 * **A walk replaces rather than adds**, which is what makes it safe to run again: the same Run put
 * to the same Segment twice leaves one set of efforts, and a Run that has stopped being eligible —
 * marked a Walk an hour later — has its efforts taken off it by the very same pass rather than by a
 * second door that could be forgotten.
 *
 * **Each side marks its own debt paid, and only once the walk has returned**
 * ([Segment.historyTimed], [com.example.runningapp.data.RunnerSession.segmentsTimed]). Both walks
 * run outside any screen and can be minutes long, so both can be lost to a process being reclaimed;
 * a debt left standing costs one repeated walk at the next launch, and a debt marked early would
 * cost the runner a Segment that quietly claims they have never run it.
 */
class SegmentTiming(private val store: SegmentTimingStore) {

    /**
     * Times a Segment against every Run in history — what a newly cut Segment is given at birth, and
     * what one cut before any of this shipped is given at the next launch.
     *
     * Runs that can hold no effort are passed over rather than written as nothing. There is nothing
     * of this Segment's to clear: either it did not exist a moment ago, or nothing has ever been
     * written for it.
     *
     * The list read at the top says which Runs to visit, and nothing more than that: what each one
     * *is* comes from the row read at the moment of timing ([time]), because this walk can be minutes
     * long and the runner is free the whole time.
     *
     * **The debt is left standing where any one Run moved under the measuring**, rather than the walk
     * claiming to have covered a Run whose efforts it abandoned. The cost is one repeated walk at the
     * next launch, which is arithmetic and nothing else; the cost of marking it anyway is a Segment
     * that says the whole of history has been put to it while one Run's answer is missing.
     */
    suspend fun timeAgainstHistory(segmentId: Long) {
        val segment = store.segment(segmentId) ?: return
        val ground = groundOf(segment)

        var efforts = 0
        var abandoned = false
        if (ground != null) {
            store.runs().filter { it.mayHoldSegmentEfforts() }.forEach { listed ->
                val held = time(segment.id, listed.id, ground)
                if (held == null) abandoned = true else efforts += held
            }
        }
        if (abandoned) {
            Log.d(TAG, "Segment $segmentId still owes a walk of history; a Run moved while it ran")
            return
        }
        store.markSegmentTimed(segment.id)
        Log.d(TAG, "Segment $segmentId has $efforts effort(s) in history")
    }

    /**
     * Times a Run against every Segment there is — what a Run gets when it finishes, and what it
     * gets again whenever the runner's word about it changes.
     *
     * A Run that may hold no efforts is still put to every Segment, and writes none at each. That is
     * the Walk case, and it is why this is the only door: a Run marked a Walk three weeks later
     * comes back through here and the very same pass takes its times off every leaderboard they are
     * on. Unmarking comes back through it too, and measures them again.
     *
     * The opening read only asks whether there is still a Run here at all. What it is — Run or Walk —
     * is asked at each Segment in turn ([time]), because a runner with many Segments can change their
     * word part-way down the list.
     */
    suspend fun timeAgainstEverySegment(sessionId: Long) {
        store.run(sessionId) ?: return

        var efforts = 0
        var abandoned = false
        store.segments().forEach { segment ->
            val ground = groundOf(segment) ?: return@forEach
            val held = time(segment.id, sessionId, ground)
            if (held == null) abandoned = true else efforts += held
        }
        if (abandoned) {
            // Left owing a walk, which is the safe thing to be left holding: the runner's own mark
            // lifted this debt before it changed the row ([SessionDao.clearSegmentsTimed]) and its
            // own walk is what pays it, so a debt marked here would be this pass claiming to have
            // covered an answer it threw away.
            Log.d(TAG, "Run $sessionId still owes a walk of the Segments; it moved while one ran")
            return
        }
        store.markRunTimed(sessionId)
        Log.d(TAG, "Run $sessionId holds $efforts segment effort(s)")
    }

    /**
     * Pays every debt either side is carrying — the launch pass (#70).
     *
     * Segments first, and that ordering is worth a line. A Segment's walk covers every Run there is,
     * so paying those first leaves the Runs below with nothing left to find in the overwhelming case
     * — a runner who has just upgraded owes a walk on every Segment they ever cut and on no Run at
     * all. The other way round, a Run finished during a process that died would be walked against
     * Segments that were about to walk it back.
     */
    suspend fun payWhatIsOwed() {
        val segments = store.segmentsMissingHistory()
        segments.forEach { timeAgainstHistory(it.id) }
        val runs = store.runsMissingTiming()
        runs.forEach { timeAgainstEverySegment(it) }
        if (segments.isNotEmpty() || runs.isNotEmpty()) {
            Log.d(TAG, "Timed ${segments.size} segment(s) against history and ${runs.size} run(s) against the segments")
        }
    }

    /**
     * One Run against one Segment, written down. Returns how many efforts it turned out to hold, or
     * null where the write was abandoned because the Run moved.
     *
     * **The Run is asked about by id and read afresh here**, immediately before its efforts are
     * replaced, and neither walk above may hand a row down instead. Both walks run outside any screen
     * and can be minutes long, and the runner is free the whole time: mark a Run a Walk mid-walk and
     * its own pass ([timeAgainstEverySegment]) takes the efforts off, so a walk still working from
     * the row as it was would put them straight back on and mark both sides' debts paid, leaving no
     * later launch to mend it. Stating the rule at the one door both walks come through is what stops
     * a third walk added later from reintroducing that.
     *
     * **And the read is not the whole of it, because the measuring sits between the read and the
     * write** (#338, #343). Measuring one long track is seconds and a Segment's walk of history is
     * minutes of them, and the runner is free in that window too: a Walk marked there has its efforts
     * deleted by its own pass and then written straight back by this one. No third re-read closes
     * that — every read has a window after it — so the check travels *into* the write
     * ([SegmentTimingStore.replaceEffortsUnlessTheRunMoved]), where the row cannot move between being
     * looked at and being acted on.
     *
     * Abandoning is safe, and is why both walks above leave their debt standing when it happens: the
     * mark that overtook this measurement lifts the Run's own Segment debt before it changes the row
     * and re-walks it afterwards, and the launch pass sweeps up whatever is still owed. An effort
     * banked for a Run nobody has is what could not be undone.
     *
     * A Run that is gone, or that may hold no efforts, is written as nothing rather than skipped —
     * that empty write is how a Walk's times come off the leaderboards it was on.
     */
    private suspend fun time(segmentId: Long, sessionId: Long, ground: List<MapFix>): Int? {
        val run = store.run(sessionId)
        val traversals = if (run != null && run.mayHoldSegmentEfforts()) {
            segmentTraversalsIn(ground, segmentTrackOf(measureTrack(store.track(sessionId))))
        } else {
            emptyList()
        }
        val written = store.replaceEffortsUnlessTheRunMoved(
            segmentId,
            sessionId,
            traversals.map {
                SegmentEffort(
                    segmentId = segmentId,
                    sessionId = sessionId,
                    startedAtMillis = it.startedAtMillis,
                    finishedAtMillis = it.finishedAtMillis,
                )
            },
            measuredAs = run,
        )
        if (!written) {
            Log.d(TAG, "Run $sessionId changed while segment $segmentId was measuring it; wrote nothing")
            return null
        }
        return traversals.size
    }

    /** A Segment's line, or null for a row whose polyline holds no line to run over. */
    private fun groundOf(segment: Segment): List<MapFix>? =
        RoutePolyline.decode(segment.polyline)
            .map { MapFix(it.latitude, it.longitude) }
            .takeIf { it.size >= 2 }

    private companion object {
        const val TAG = "Segments"
    }
}

/**
 * Whether a Run may hold Segment efforts at all — the record book's own rules of entry
 * ([com.example.runningapp.analysis.bestEffortsOf]), said again for the one thing they are about
 * here.
 *
 * - **A Run still being recorded holds nothing.** Its track is still arriving, so an effort measured
 *   now would be measured over half a Run.
 * - **A Walk holds nothing** (#275). A walk up the hill taking the PR off the runs up it makes the
 *   Segment meaningless, in exactly the way a walk taking a medal makes the trophy case meaningless.
 *   This is the runner's own word rather than a fact about the recording, and it can arrive long
 *   after the Run — which is why marking one mends the Segments behind it
 *   ([SegmentTiming.timeAgainstEverySegment]).
 * - **A treadmill Run holds nothing.** It has no track, so there is no ground to put to a Segment;
 *   and unlike a Best Effort there is nothing a runner could state instead, because a console cannot
 *   know it was on the runner's hill.
 */
fun RunnerSession.mayHoldSegmentEfforts(): Boolean = isFinished() && !isWalk && !isTreadmill()

/**
 * Whether two readings of the same Run would be worth the same at a Segment — everything the timing
 * decides by, and nothing else (#338, #343).
 *
 * The three things [mayHoldSegmentEfforts] asks, because those are the three that can turn a Run's
 * efforts into no efforts or back: a Run finishing, being marked a Walk, becoming a treadmill Run.
 * A feel, a note or an Effort Score written while a track is being measured moves no waypoint and is
 * therefore no reason to throw a measurement away.
 *
 * The track is not among them, and cannot be. A Run's fixes stop arriving when it ends, and the end
 * is [RunnerSession.endTime], which is here.
 */
fun RunnerSession.holdsEffortsAs(other: RunnerSession): Boolean =
    endTime == other.endTime && isWalk == other.isWalk && runMode == other.runMode
