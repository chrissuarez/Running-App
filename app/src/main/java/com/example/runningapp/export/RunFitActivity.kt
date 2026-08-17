package com.example.runningapp.export

import com.example.runningapp.analysis.RunAnalysis
import com.example.runningapp.analysis.Split
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.heartRatesByWallSecond
import com.example.runningapp.data.paceClockSeconds
import com.example.runningapp.run.RunMode
import java.time.ZoneId

/**
 * Turns what the app recorded — a session, its GPS track, its heart-rate samples and the splits its
 * own page shows — into the [FitActivity] the writer encodes (#218).
 *
 * Pure, like [RunGpxTrack] and for the same reason: the things that can quietly go wrong here are
 * which heart rate lands on which moment, and whether the file's summary is the app's summary. Both
 * are pinned by unit tests rather than discovered in Garmin Connect.
 *
 * The difference from GPX is what a moment is allowed to be. A GPX trackpoint must carry a position,
 * so a run with no GPS exports as an empty track; a FIT record need not, so a treadmill run's
 * heart-rate trace comes out whole. Records are therefore built from *both* streams — every second
 * that either the track or the strap recorded something — rather than from the track alone.
 */
object RunFitActivity {

    fun build(
        session: RunnerSession,
        trackPoints: List<TrackPoint>,
        hrSamples: List<HrSample>,
        analysis: RunAnalysis,
    ): FitActivity {
        // Both streams on the wall clock, the only axis they share — see [heartRatesByWallSecond].
        val records = recordsOf(trackPoints, heartRatesByWallSecond(session, hrSamples))
        val elapsedMillis = session.durationSeconds * 1000L
        val movingMillis = session.paceClockSeconds * 1000L
        val distanceMeters = session.distanceKm * 1000.0
        // Wide enough to hold every record: a fix stamped a moment before the run's own start would
        // otherwise sit outside the file's own timer, which a reader is entitled to distrust.
        val startTimeMillis = minOf(session.startTime, records.firstOrNull()?.timeMillis ?: session.startTime)
        val endTimeMillis = maxOf(
            maxOf(session.endTime, session.startTime + elapsedMillis),
            records.lastOrNull()?.timeMillis ?: session.startTime,
        )
        return FitActivity(
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            elapsedMillis = elapsedMillis,
            movingMillis = movingMillis,
            distanceMeters = distanceMeters,
            sport = sportOf(session),
            records = records,
            laps = lapsOf(
                splits = analysis.splits,
                wholeRun = FitLap(
                    startTimeMillis = startTimeMillis,
                    endTimeMillis = endTimeMillis,
                    movingMillis = movingMillis,
                    distanceMeters = distanceMeters,
                ),
            ),
            averageBpm = session.avgBpm.takeIf { it > 0 },
            maxBpm = session.maxBpm.takeIf { it > 0 },
            ascentMeters = analysis.elevationGainMeters,
        )
    }

    /** This Run's `.fit` file name. */
    fun fileName(session: RunnerSession, zoneId: ZoneId = ZoneId.systemDefault()): String =
        RunExportName.fileName(session, FitWriter.FILE_EXTENSION, zoneId)

    /**
     * Every second of the run anything was recorded for, in time order.
     *
     * A track fix brings its position and its height; a heart rate on a second the track missed
     * becomes a record of its own. So a run with GPS exports a fix-by-fix route with heart rates on
     * it, a run with none exports a heart-rate trace, and a run whose signal came and went exports
     * both across the whole of it — where GPX would have dropped the heart rates recorded during the
     * outage on the floor.
     *
     * No record states how far the run had gone by it. FIT allows one, and it would be a second,
     * quieter claim about the Run's distance measured a different way from the first: the summary
     * carries the distance the recorder banked as it ran, and anything derived here would be the
     * track re-measured on read. Two numbers for one distance is the disagreement this export exists
     * to end ([ADR 0017](docs/adr/0017-an-export-states-the-run-it-does-not-imply-it.md)), so the
     * distance is stated once, in the summary and its laps, and nowhere else.
     */
    private fun recordsOf(
        trackPoints: List<TrackPoint>,
        bpmByWallSecond: Map<Long, Int>,
    ): List<FitRecord> {
        val ordered = trackPoints.sortedBy { it.timestampMillis }
        val fixes = ordered.map { point ->
            FitRecord(
                timeMillis = point.timestampMillis,
                latitude = point.latitude,
                longitude = point.longitude,
                altitudeMeters = point.altitudeMeters,
                heartRateBpm = bpmByWallSecond.nearestBpm(point.timestampMillis / 1000),
            )
        }
        val secondsWithAFix = ordered.mapTo(mutableSetOf()) { it.timestampMillis / 1000 }
        val strapOnly = bpmByWallSecond
            .filterKeys { it !in secondsWithAFix }
            .map { (second, bpm) -> FitRecord(timeMillis = second * 1000, heartRateBpm = bpm) }
        return (fixes + strapOnly).sortedBy { it.timeMillis }
    }

    /**
     * The run's kilometres as FIT laps, or the whole run as one lap where there are none to use.
     *
     * A FIT activity is required to hold at least one lap, and the app has no splits for a treadmill
     * Run or for a run with no usable track. [wholeRun] — one lap spanning the whole of it — says
     * exactly what is known about such a run, its distance and its clock, without inventing
     * kilometre markers the app never placed.
     */
    private fun lapsOf(splits: List<Split>, wholeRun: FitLap): List<FitLap> {
        if (splits.isEmpty()) return listOf(wholeRun)
        return splits.map { split ->
            FitLap(
                startTimeMillis = split.startTimeMillis,
                endTimeMillis = split.endTimeMillis,
                movingMillis = split.movingMillis,
                distanceMeters = split.distanceMeters,
                averageBpm = split.averageBpm,
                ascentMeters = split.elevationGainMeters,
            )
        }
    }

    /**
     * What FIT should call this run.
     *
     * A Run the runner marked a walk is a walk whatever it was recorded as (#275) — that mark is the
     * runner correcting the app, and it is the last word here as it is everywhere else.
     */
    private fun sportOf(session: RunnerSession): FitSport = when {
        session.isWalk -> FitSport.WALK
        RunMode.ofSettingValue(session.runMode) == RunMode.TREADMILL -> FitSport.TREADMILL_RUN
        else -> FitSport.RUN
    }
}
