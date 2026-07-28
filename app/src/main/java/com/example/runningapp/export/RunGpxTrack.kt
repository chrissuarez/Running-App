package com.example.runningapp.export

import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns what the app recorded — a session, its GPS track and its heart-rate samples — into the
 * [GpxTrack] the writer serialises (#84).
 *
 * Pure, so the two things that can quietly go wrong (which heart rate lands on which point, and how
 * a run is named) are pinned by unit tests rather than discovered on a phone.
 */
object RunGpxTrack {

    /**
     * How far a heart-rate sample may sit from a track point and still describe it. Samples are
     * written once a second but only while the strap reports a beat, so short drop-outs leave gaps;
     * five seconds bridges a gap without inventing a reading for a real disconnection.
     *
     * Measured from the point, not across the gap: a point is described by any real reading taken
     * within five seconds of it, on either side. A ten-second drop-out is therefore covered from
     * both ends and a longer one is not covered in the middle, which is the intent — no point ever
     * carries a heart rate more than five seconds removed from a beat the strap actually reported.
     */
    private const val HR_MATCH_TOLERANCE_SECONDS = 5L

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

    private val NAME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.UK)

    private val FILE_NAME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.UK)

    fun build(
        session: RunnerSession,
        trackPoints: List<TrackPoint>,
        hrSamples: List<HrSample>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): GpxTrack {
        // Both streams are put on the wall clock, the only axis they share: a track point is stamped
        // with the time of its GPS fix, while a sample's elapsedSeconds counts running seconds and
        // stands still through a pause.
        //
        // Rows written before v16 have no stamp of their own, and elapsed seconds stand in for one.
        // On a run that paused, the two axes have come apart by the length of the pause, so those
        // readings land late by up to that much — and only after the pause, since before it the two
        // agree. That is accepted deliberately: every run in the history this shipped against is a
        // legacy one, most of them paused, and a heart-rate graph carrying a known bounded offset is
        // worth more to a runner than no graph at all. Runs recorded from v16 on carry the wall
        // clock themselves and are exact, so this fades with the old rows rather than living on.
        val bpmByWallSecond = hrSamples.associate { sample ->
            val atMillis = sample.timestampMillis ?: (session.startTime + sample.elapsedSeconds * 1000)
            // The raw reading, not the smoothed one: `<hr>` means the heart rate measured at that
            // point, and every reader does its own smoothing for display. The smoothed number is a
            // coaching aid — averaging twice would only flatten the run into something it wasn't.
            atMillis / 1000 to sample.rawBpm
        }
        // Split first, then describe: where the run stopped is a fact about the recording, and only
        // the stored points still carry it.
        val stretches = trackPoints.sortedBy { it.timestampMillis }.splitWhereTheRunStopped()
        return GpxTrack(
            name = runName(session, zoneId),
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

    fun runName(session: RunnerSession, zoneId: ZoneId = ZoneId.systemDefault()): String =
        "Run " + NAME_FORMAT.format(Instant.ofEpochMilli(session.startTime).atZone(zoneId))

    /**
     * Lower-case and hyphenated: it becomes a real file name in Drive, on a laptop, in an email.
     *
     * The run's own id closes the name off: exports share one cache directory and a later write to
     * the same name overwrites the earlier file, which would hand a share still in flight the wrong
     * run. Two runs can share a local date and minute — back-to-back intervals, or the hour a clock
     * change repeats — but never an id.
     */
    fun fileName(session: RunnerSession, zoneId: ZoneId = ZoneId.systemDefault()): String =
        "run-" + FILE_NAME_FORMAT.format(Instant.ofEpochMilli(session.startTime).atZone(zoneId)) +
            "-" + session.id + "." + GpxWriter.FILE_EXTENSION

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

    private fun Map<Long, Int>.nearestBpm(atSecond: Long): Int? {
        for (offset in 0..HR_MATCH_TOLERANCE_SECONDS) {
            // Earlier before later on a tie: the reading already taken describes the runner better
            // than one that has not happened yet.
            get(atSecond - offset)?.let { return it }
            get(atSecond + offset)?.let { return it }
        }
        return null
    }
}
