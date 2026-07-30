package com.example.runningapp.data

import com.example.runningapp.recording.geodesicDistanceMeters

/** The distance the two 5K Stage requirements are stated in, in metres. */
const val FIVE_K_METERS = 5_000.0

/**
 * The quickest continuous [targetMeters] the runner covered inside a finished Run, in seconds —
 * the run's best effort at that distance, as Strava reports one.
 *
 * This is what a requirement stated as a distance in a time actually asks for (#182). The Run's own
 * clock cannot answer it: an eight-minute warm-up walk and a three-minute cool-down are inside
 * `durationSeconds`, so a genuine 24-minute 5K reads as 35 and fails. A window measured over the
 * track answers it, because a warm-up simply falls outside the fastest window rather than having to
 * be identified and subtracted — nothing records where the Phases changed, so subtracting them is
 * not available anyway.
 *
 * Returns null when the track never covers [targetMeters] in one continuous stretch of recording —
 * a treadmill Run with no track at all, a Run that stopped short, or a Run whose recording broke.
 * Null means "not established here", never "failed": the coach is told the difference, because
 * a Stage must not be graduated on an absence.
 *
 * Rules of the window, both deliberately conservative — an effort this cannot see reads as no
 * effort, which loses the runner a graduation, while an effort it flatters would hand them one they
 * did not run:
 *
 * - **A recorded pause is passed through, contributing neither time nor distance.** The Run's own
 *   clock stops at a pause, so charging its seconds here would measure something the app does not
 *   measure anywhere else; and GPS is torn down across it, so the ground covered is unrecorded and
 *   cannot be credited either. A runner who paused at a crossing keeps their 5K.
 * - **A gap in the recording with no pause behind it ends the effort.** Nothing says the runner
 *   stopped, so those seconds were run seconds; passing them through free would read a GPS dropout
 *   as a sprint, and that is the direction that graduates a Stage nobody earned.
 *
 * Pass the same accuracy-filtered points the map, the distance total and moving time are built from
 * ([SessionRepository.getTrackPointsForMap]). A rejected wild fix left in would read as a sprint.
 */
fun measureFastestEffortSeconds(points: List<TrackPoint>, targetMeters: Double): Long? {
    if (targetMeters <= 0 || points.size < 2) return null

    val ordered = points.sortedBy { it.timestampMillis }

    // Distance and time along the track, both frozen across a pause and both reset by a break, so
    // that a window is only ever measured over ground and seconds that were actually recorded.
    val cumulativeMeters = DoubleArray(ordered.size)
    val cumulativeMillis = LongArray(ordered.size)
    // Where the current unbroken stretch of recording starts. A window may not reach back past it.
    var stretchStart = 0
    var bestSeconds = Long.MAX_VALUE
    // The oldest point the window still reaches back to.
    var windowStart = 0

    for (i in 1 until ordered.size) {
        val previous = ordered[i - 1]
        val current = ordered[i]
        val legMillis = current.timestampMillis - previous.timestampMillis
        val legMeters = geodesicDistanceMeters(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
        )
        // Two fixes stamped the same second carry the leg no time to be run in, so it carries no
        // ground either — counted, it would be distance for free.
        val pausedHere = current.startsAfterPause || legMillis <= 0
        val brokeHere = !pausedHere && legMillis > TRACK_BREAK_MS

        if (brokeHere) {
            // Start again on the far side: nothing before the break can join anything after it.
            stretchStart = i
            windowStart = i
            cumulativeMeters[i] = 0.0
            cumulativeMillis[i] = 0L
            continue
        }
        if (pausedHere) {
            cumulativeMeters[i] = cumulativeMeters[i - 1]
            cumulativeMillis[i] = cumulativeMillis[i - 1]
        } else {
            cumulativeMeters[i] = cumulativeMeters[i - 1] + legMeters
            cumulativeMillis[i] = cumulativeMillis[i - 1] + legMillis
        }

        // Pull the window's start forward while the distance behind it is still enough, so what is
        // measured is the tightest window ending here rather than the whole stretch.
        if (windowStart < stretchStart) windowStart = stretchStart
        while (
            windowStart + 1 <= i &&
            cumulativeMeters[i] - cumulativeMeters[windowStart + 1] >= targetMeters
        ) {
            windowStart++
        }
        val windowMeters = cumulativeMeters[i] - cumulativeMeters[windowStart]
        if (windowMeters < targetMeters) continue

        // The window's first leg is longer than the effort needs, so only the part of it that
        // carries the runner to the target counts — measured at that leg's own speed. Without this
        // trim a run recorded at one fix a minute could only ever report whole-minute efforts.
        val overshootMeters = windowMeters - targetMeters
        val firstLegMeters = cumulativeMeters[windowStart + 1] - cumulativeMeters[windowStart]
        val firstLegMillis = cumulativeMillis[windowStart + 1] - cumulativeMillis[windowStart]
        val trimmedMillis = if (firstLegMeters > 0) {
            (overshootMeters.coerceAtMost(firstLegMeters) / firstLegMeters * firstLegMillis).toLong()
        } else {
            0L
        }
        val windowMillis = (cumulativeMillis[i] - cumulativeMillis[windowStart] - trimmedMillis)
            .coerceAtLeast(0L)
        val windowSeconds = (windowMillis + 500) / 1000
        if (windowSeconds < bestSeconds) bestSeconds = windowSeconds
    }

    return bestSeconds.takeIf { it != Long.MAX_VALUE }
}
