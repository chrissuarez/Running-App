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
     */
    private const val HR_MATCH_TOLERANCE_SECONDS = 5L

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
        // stands still through a pause. Rows written before v16 have no stamp of their own, and for
        // them elapsed seconds are the best available answer — exact for a run that was never paused.
        val bpmByWallSecond = hrSamples.associate { sample ->
            val atMillis = sample.timestampMillis ?: (session.startTime + sample.elapsedSeconds * 1000)
            // The raw reading, not the smoothed one: `<hr>` means the heart rate measured at that
            // point, and every reader does its own smoothing for display. The smoothed number is a
            // coaching aid — averaging twice would only flatten the run into something it wasn't.
            atMillis / 1000 to sample.rawBpm
        }
        return GpxTrack(
            name = runName(session, zoneId),
            startTimeMillis = session.startTime,
            points = trackPoints.sortedBy { it.timestampMillis }.map { point ->
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

    fun runName(session: RunnerSession, zoneId: ZoneId = ZoneId.systemDefault()): String =
        "Run " + NAME_FORMAT.format(Instant.ofEpochMilli(session.startTime).atZone(zoneId))

    /** Lower-case and hyphenated: it becomes a real file name in Drive, on a laptop, in an email. */
    fun fileName(session: RunnerSession, zoneId: ZoneId = ZoneId.systemDefault()): String =
        "run-" + FILE_NAME_FORMAT.format(Instant.ofEpochMilli(session.startTime).atZone(zoneId)) +
            "." + GpxWriter.FILE_EXTENSION

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
