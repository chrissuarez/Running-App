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
        // Rows written before v16 have no stamp of their own. Elapsed seconds stand in for one, but
        // only for a run that was never paused: on a paused run the two axes have come apart by the
        // length of the pause, so placing a legacy sample by its elapsed seconds would hand a point
        // a heart rate the runner had minutes later. A run with no clock to trust exports its route
        // without heart rate — a reader can see that nothing was recorded, but not that what was
        // recorded is wrong.
        val legacySamplesArePlaceable = session.ranWithoutPausing()
        val bpmByWallSecond = hrSamples.mapNotNull { sample ->
            val atMillis = sample.timestampMillis
                ?: (session.startTime + sample.elapsedSeconds * 1000).takeIf { legacySamplesArePlaceable }
                ?: return@mapNotNull null
            // The raw reading, not the smoothed one: `<hr>` means the heart rate measured at that
            // point, and every reader does its own smoothing for display. The smoothed number is a
            // coaching aid — averaging twice would only flatten the run into something it wasn't.
            atMillis / 1000 to sample.rawBpm
        }.toMap()
        val points = trackPoints.sortedBy { it.timestampMillis }.map { point ->
            GpxTrackPoint(
                latitude = point.latitude,
                longitude = point.longitude,
                elevationMeters = point.altitudeMeters,
                timeMillis = point.timestampMillis,
                heartRateBpm = bpmByWallSecond.nearestBpm(point.timestampMillis / 1000)
            )
        }
        return GpxTrack(
            name = runName(session, zoneId),
            startTimeMillis = session.startTime,
            segments = points.splitWhereTheRunStopped()
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
     * Breaks the route wherever the recording stopped for longer than [ROUTE_BREAK_SECONDS].
     *
     * A manual pause tears down the GPS stream, so nothing is recorded between the last fix before
     * it and the first after the resume — and the runner may be somewhere else by then. Left as one
     * stretch, a reader joins those two fixes with a straight line and counts it as distance run;
     * the app deliberately does not (`SessionRecorder.discardLastFix`), and the exported file should
     * not disagree with the run it describes. A long loss of signal breaks the route here too, for
     * the same reason: nothing was recorded in between, so nothing should be drawn through it.
     */
    private fun List<GpxTrackPoint>.splitWhereTheRunStopped(): List<GpxTrackSegment> {
        if (isEmpty()) return emptyList()
        val segments = mutableListOf<MutableList<GpxTrackPoint>>(mutableListOf(first()))
        zipWithNext { previous, point ->
            if (point.timeMillis - previous.timeMillis > ROUTE_BREAK_SECONDS * 1000) {
                segments += mutableListOf(point)
            } else {
                segments.last() += point
            }
        }
        return segments.map { GpxTrackSegment(it) }
    }

    /**
     * Whether the run's two clocks agree: running seconds against the wall time it spanned. A pause
     * is the only thing that parts them, so a run whose duration matches its span was never paused,
     * and its elapsed seconds are wall seconds. The few seconds of slack absorb the rounding and the
     * moment between the last tick and the row being stamped as finished.
     */
    private fun RunnerSession.ranWithoutPausing(): Boolean {
        if (endTime <= startTime) return false
        val wallSeconds = (endTime - startTime) / 1000
        return wallSeconds - durationSeconds <= HR_MATCH_TOLERANCE_SECONDS
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
