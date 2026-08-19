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
 * **A scan replaces rather than adds**, which is what makes it safe to run again: the same Run put
 * to the same Segment twice leaves one set of efforts, and a Run that has stopped being eligible —
 * marked a Walk an hour later — has its efforts taken off it by the very same pass rather than by a
 * second door that could be forgotten.
 */
class SegmentTiming(
    private val readSegments: suspend () -> List<Segment>,
    private val readSegment: suspend (Long) -> Segment?,
    private val readRuns: suspend () -> List<RunnerSession>,
    private val readRun: suspend (Long) -> RunnerSession?,
    private val readTrack: suspend (Long) -> List<TrackPoint>,
    private val writeEfforts: suspend (segmentId: Long, sessionId: Long, List<SegmentEffort>) -> Unit,
) {

    /**
     * Times a Segment against every Run in history — what a newly cut Segment is given at birth.
     *
     * Runs that can hold no effort are passed over rather than written as nothing. There is nothing
     * of this Segment's to clear: it did not exist a moment ago.
     */
    suspend fun timeAgainstHistory(segmentId: Long) {
        val segment = readSegment(segmentId) ?: return
        val ground = groundOf(segment) ?: return

        var efforts = 0
        readRuns().filter { it.mayHoldSegmentEfforts() }.forEach { run ->
            efforts += time(segment.id, run, ground)
        }
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
        val run = readRun(sessionId) ?: return
        val segments = readSegments()
        if (segments.isEmpty()) return

        var efforts = 0
        segments.forEach { segment ->
            val ground = groundOf(segment) ?: return@forEach
            efforts += time(segment.id, run, ground)
        }
        Log.d(TAG, "Run $sessionId holds $efforts segment effort(s)")
    }

    /** One Run against one Segment, written down. Returns how many efforts it turned out to hold. */
    private suspend fun time(segmentId: Long, run: RunnerSession, ground: List<MapFix>): Int {
        val traversals = if (run.mayHoldSegmentEfforts()) {
            segmentTraversalsIn(ground, segmentTrackOf(measureTrack(readTrack(run.id))))
        } else {
            emptyList()
        }
        writeEfforts(
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
