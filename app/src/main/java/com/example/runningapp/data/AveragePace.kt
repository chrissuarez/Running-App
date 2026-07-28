package com.example.runningapp.data

import kotlin.math.roundToLong

/**
 * A finished run's average pace, derived from the run's own totals rather than stored.
 *
 * The pace the runner sees mid-run is a rolling 15-second window
 * ([com.example.runningapp.recording.SessionRecorder]) - the right thing for a live tile, and the
 * wrong thing for a summary. Reading that window at the moment STOP was pressed is what made a
 * 4.53 km run in 37:39 report 29:05 /km: it was the pace of someone standing still getting their
 * breath back (#163).
 *
 * [durationSeconds] is the time the Run was not paused - [com.example.runningapp.run.Run] banks
 * paused seconds into `secondsPaused`, never into `secondsRunning`. That is this app's moving
 * time, and it is not Strava's: on the run in #163 nothing was ever paused, so this clock read
 * 37:39 against Strava's 36:56, and the two paces stood ~9s/km apart for that reason alone. A gap
 * that size against Strava is the two definitions differing, not a fault here.
 *
 * Returns 0.0 for a run that never moved or never ran; the UI reads that as "--:--".
 */
fun averagePaceMinPerKm(durationSeconds: Long, distanceKm: Double): Double {
    if (durationSeconds <= 0 || distanceKm <= 0.0) return 0.0
    return (durationSeconds / 60.0) / distanceKm
}

/** [averagePaceMinPerKm] as `m:ss`, or `--:--` when there is no pace to show. */
fun formatAveragePace(durationSeconds: Long, distanceKm: Double): String =
    formatMinutesPerKm(averagePaceMinPerKm(durationSeconds, distanceKm))

/**
 * A min/km pace as `m:ss`, or `--:--` when there is none. The one place a pace becomes minutes and
 * seconds, so the live tile and a finished run's summary can never round the same pace differently.
 */
fun formatMinutesPerKm(paceMinPerKm: Double): String {
    if (paceMinPerKm <= 0.0) return "--:--"
    // Round to whole seconds first, then split - rounding the seconds component on its own lets
    // 8:59.7 render as "8:60".
    val totalSeconds = (paceMinPerKm * 60.0).roundToLong()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
