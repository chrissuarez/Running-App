package com.example.runningapp.export

import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.heartRatesByWallSecond
import com.example.runningapp.data.TrackPoint
import java.time.ZoneId

/**
 * Turns what the app recorded — a session, its GPS track and its heart-rate samples — into the
 * [GpxTrack] the writer serialises (#84).
 *
 * Pure, so the thing that can quietly go wrong — where the route is allowed to be joined up — is
 * pinned by unit tests rather than discovered on a phone. What a Run is called is
 * [RunExportName]'s, and which heart rate lands on which moment is [heartRatesByWallSecond]'s; both
 * are shared with the FIT export so the two files cannot disagree about one Run.
 */
object RunGpxTrack {

    /**
     * How long the route may go unrecorded before it counts as broken rather than sparse. Fixes
     * arrive about a second apart, and a manual pause costs the pause itself plus re-acquiring GPS
     * on resume — so twenty seconds sits well above the gaps of a run in progress and well below
     * any pause a runner actually takes.
     *
     * It is the fallback, not the test: a recorded resume breaks the route however short the pause
     * was. This catches only what nothing recorded — a long loss of signal, and pauses on runs saved
     * before the boundary was recorded.
     */
    private const val ROUTE_BREAK_SECONDS = 20L

    fun build(
        session: RunnerSession,
        trackPoints: List<TrackPoint>,
        hrSamples: List<HrSample>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): GpxTrack {
        // Both streams on the wall clock, the only axis they share — see [heartRatesByWallSecond].
        val bpmByWallSecond = heartRatesByWallSecond(session, hrSamples)
        // Split first, then describe: where the run stopped is a fact about the recording, and only
        // the stored points still carry it.
        val stretches = trackPoints.sortedBy { it.timestampMillis }.splitWhereTheRunStopped()
        return GpxTrack(
            name = RunExportName.runName(session, zoneId),
            startTimeMillis = session.startTime,
            segments = stretches.map { stretch ->
                GpxTrackSegment(
                    stretch.map { point ->
                        GpxTrackPoint(
                            latitude = point.latitude,
                            longitude = point.longitude,
                            elevationMeters = point.altitudeMeters,
                            timeMillis = point.timestampMillis,
                            heartRateBpm = bpmByWallSecond.nearestBpm(point.timestampMillis / 1000)
                        )
                    }
                )
            }
        )
    }

    /**
     * Breaks the route wherever the run stopped, so a reader has nothing to draw across it.
     *
     * A pause leaves the runner somewhere the track never followed them to: the app refuses to count
     * that leg towards its own distance (`SessionRecorder.discardLastFix`), and the exported file
     * should not disagree with the run it describes. Left as one stretch, a reader joins the last
     * fix before the pause to the first after it with a straight line and counts it as distance run.
     *
     * Two things break it, and the first is why the second is not enough on its own:
     *
     *  - the resume being recorded on the fix that made it ([TrackPoint.startsAfterPause]).
     *    A pause of a few seconds — stop, cross, carry on — leaves a gap no longer than a sparse
     *    patch of a run in progress, so no length of gap can tell the two apart.
     *  - a gap longer than [ROUTE_BREAK_SECONDS], which catches what nothing recorded: a long loss
     *    of signal, and the pauses on runs saved before the boundary was, where it was never written
     *    down and only a gap is left to find it by.
     */
    private fun List<TrackPoint>.splitWhereTheRunStopped(): List<List<TrackPoint>> {
        if (isEmpty()) return emptyList()
        val stretches = mutableListOf(mutableListOf(first()))
        zipWithNext { previous, point ->
            val stoppedHere = point.startsAfterPause ||
                point.timestampMillis - previous.timestampMillis > ROUTE_BREAK_SECONDS * 1000
            if (stoppedHere) {
                stretches += mutableListOf(point)
            } else {
                stretches.last() += point
            }
        }
        return stretches
    }
}
