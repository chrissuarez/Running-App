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
    suspend fun replaceEfforts(segmentId: Long, sessionId: Long, efforts: List<SegmentEffort>)
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
     */
    suspend fun timeAgainstHistory(segmentId: Long) {
        val segment = store.segment(segmentId) ?: return
        val ground = groundOf(segment)

        var efforts = 0
        if (ground != null) {
            store.runs().filter { it.mayHoldSegmentEfforts() }.forEach { run ->
                efforts += time(segment.id, run, ground)
            }
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
     */
    suspend fun timeAgainstEverySegment(sessionId: Long) {
        val run = store.run(sessionId) ?: return

        var efforts = 0
        store.segments().forEach { segment ->
            val ground = groundOf(segment) ?: return@forEach
            efforts += time(segment.id, run, ground)
        }
        store.markRunTimed(run.id)
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

    /** One Run against one Segment, written down. Returns how many efforts it turned out to hold. */
    private suspend fun time(segmentId: Long, run: RunnerSession, ground: List<MapFix>): Int {
        val traversals = if (run.mayHoldSegmentEfforts()) {
            segmentTraversalsIn(ground, segmentTrackOf(measureTrack(store.track(run.id))))
        } else {
            emptyList()
        }
        store.replaceEfforts(
            segmentId,
            run.id,
            traversals.map {
                SegmentEffort(
                    segmentId = segmentId,
                    sessionId = run.id,
                    startedAtMillis = it.startedAtMillis,
                    finishedAtMillis = it.finishedAtMillis,
                )
            },
        )
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
