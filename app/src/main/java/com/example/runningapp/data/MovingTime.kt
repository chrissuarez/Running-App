package com.example.runningapp.data

import com.example.runningapp.recording.geodesicDistanceMeters

/**
 * Speed at or above which Strava counts a runner as moving rather than resting: a 30-minute mile,
 * which is 1609.344 m in 1800 s. Published in Strava's running glossary — "the moving threshold is
 * anything faster than a 30-minute mile pace for running activities".
 *
 * Well above this app's own auto-pause standstill bar
 * ([com.example.runningapp.recording.SessionRecorder.STANDSTILL_SPEED_THRESHOLD_MPS], 0.3 m/s):
 * auto-pause asks "has the runner stopped?", this asks "is the runner still getting anywhere?", and
 * a slow shuffle answers those two differently on purpose.
 */
const val MOVING_SPEED_THRESHOLD_MPS = 1609.344 / 1800.0

/**
 * How long the runner must stay under [MOVING_SPEED_THRESHOLD_MPS] before that spell is taken out
 * as rest. Below this, a slow spell stays in: a single dropped stride or a GPS wobble is not a
 * rest, and stopping the clock for each one would flatter every pace in the app.
 *
 * Unlike [MOVING_SPEED_THRESHOLD_MPS], Strava does not publish this, so it is calibrated against
 * runs Strava and this app both measured. Measuring each run's own exported GPX at each window,
 * against what Strava made of the same file:
 *
 * ```
 *                 0s      1s      2s      3s      4s      5s     15s    Strava
 *   28 Jul     36:30   36:44   36:54   36:57   37:01   37:11   37:39     36:56
 *   26 Jul     21:40   21:50   21:58   22:01   22:05   22:05   22:13     21:59
 * ```
 *
 * Three seconds lands within two seconds of Strava on both, and is the only window that does.
 * Fifteen removes almost nothing, because this runner's rest is many short breaks rather than one
 * long stop; zero removes far too much, because a dropped stride is not a rest. Holding the window
 * at 3s and sweeping the speed threshold instead puts the best fit at 0.894 m/s — Strava's
 * published number, arrived at independently, which is the reason to trust the pair rather than
 * either alone.
 *
 * Two runs are two data points, and both are this one runner on this one phone. The number to
 * re-check against is pace rather than moving time, since pace is what either app puts on screen:
 * both runs match Strava's to the second there (8:10 and 8:58 /km).
 */
const val REST_SUSTAINED_MS = 3_000L

/**
 * How long the track may go unrecorded before the gap counts as a break rather than a leg — the
 * fallback for a break nothing recorded, and the same number the GPX export draws its route break
 * at (`RunGpxTrack.ROUTE_BREAK_SECONDS`). Fixes arrive about a second apart, so twenty seconds sits
 * well above the gaps of a run in progress.
 *
 * A break is never moving time, whatever the two fixes either side of it imply about speed. A
 * manual pause tears the GPS stream down, so a runner who pauses, walks 400 m to a shop and resumes
 * leaves one long leg — and counting it would put moving time *above* the run's own clock.
 *
 * It is the fallback and not the rule, because a gap is weaker evidence than a record: a pause
 * shorter than this leaves no gap worth noticing, and the runner who paused at a shop door and
 * walked on afterwards would have every second of it counted as moving. Runs recorded since #84
 * write the pause down on the fix that resumed them ([TrackPoint.startsAfterPause]), which is read
 * first; the gap rule is what remains for older runs, where nothing was written down.
 */
const val TRACK_BREAK_MS = 20_000L

/**
 * A finished run's moving time, in seconds — the run's own clock minus the spells the runner spent
 * going nowhere, computed the way Strava computes it for an uploaded GPX (#163).
 *
 * Speed is taken from the track itself (the distance between consecutive fixes over the time
 * between them) rather than from each fix's reported [TrackPoint.speedMps]. That is the only
 * information Strava has when it reads a GPX file, so deriving it the same way is what makes the
 * two numbers comparable — and it means a break in the recording, where no fix arrived for minutes,
 * reads as what it was rather than as the last reported speed carrying on unwitnessed.
 *
 * Pass the same accuracy-filtered points the map and the distance total are built from
 * ([SessionRepository.getTrackPointsForMap]). A rejected wild fix left in would read as a sprint.
 */
fun measureMovingTimeSeconds(points: List<TrackPoint>): Long {
    if (points.size < 2) return 0

    val ordered = points.sortedBy { it.timestampMillis }
    var movingMs = 0L
    // The slow spell currently being accumulated. It is only rest once it outlasts
    // REST_SUSTAINED_MS; until then it is still provisionally moving time.
    var slowSpellMs = 0L

    for (i in 1 until ordered.size) {
        val previous = ordered[i - 1]
        val current = ordered[i]
        val legMs = current.timestampMillis - previous.timestampMillis
        if (legMs <= 0) continue

        val legMeters = geodesicDistanceMeters(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
        )
        // "Faster than a 30-minute mile", as Strava puts it - so exactly the threshold is not
        // moving. A leg spanning a break in the recording is rest no matter how fast it looks: the
        // run said so on the fix that resumed it, or, for a run recorded before it said so, the
        // gap itself is the only evidence there is.
        val spansBreak = current.startsAfterPause || legMs > TRACK_BREAK_MS
        when {
            // A break is settled rest, not a slow spell waiting to be judged. Banking it as one
            // would hand it back: a two-second recorded pause is shorter than the rest window, so
            // the next moving leg would restore every paused second as moving time. The slow spell
            // running into it goes too - the runner was slowing to the stop, and it is rest for
            // the same reason the stop is.
            spansBreak -> slowSpellMs = 0L
            // "Faster than a 30-minute mile", as Strava puts it - so exactly the threshold is not
            // moving.
            legMeters / (legMs / 1000.0) > MOVING_SPEED_THRESHOLD_MPS -> {
                movingMs += legMs + keptFrom(slowSpellMs)
                slowSpellMs = 0L
            }
            else -> slowSpellMs += legMs
        }
    }

    // A run that ended slowly - stood at the finish line getting a breath back before pressing
    // stop - closes on an unresolved slow spell, judged by the same rule. A run that never cleared
    // the threshold at all keeps nothing: a spell too short to be rest is still not moving on its
    // own, and a run started and stopped by mistake at a standstill should read zero, not two
    // seconds of movement it never made.
    if (movingMs == 0L) return 0
    return (movingMs + keptFrom(slowSpellMs)) / 1000
}

/** A slow spell counts as moving until it outlasts [REST_SUSTAINED_MS]; after that, none of it does. */
private fun keptFrom(slowSpellMs: Long): Long = if (slowSpellMs > REST_SUSTAINED_MS) 0L else slowSpellMs
