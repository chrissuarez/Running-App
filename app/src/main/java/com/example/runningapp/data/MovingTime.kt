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
 * as rest. Below this, a slow spell stays in: a road crossing, a gate, a hesitation at a junction
 * or a GPS wobble is not a rest, and stopping the clock for each one would flatter every pace in
 * the app.
 */
const val REST_SUSTAINED_MS = 15_000L

/**
 * How long the track may go unrecorded before the gap counts as a break rather than a leg. Fixes
 * arrive about a second apart, so twenty seconds sits well above the gaps of a run in progress and
 * well below any pause a runner actually takes — the same reasoning, and the same number, as the
 * route break the GPX export draws (`RunGpxTrack.ROUTE_BREAK_SECONDS`).
 *
 * A break is never moving time, whatever the two fixes either side of it imply about speed. A
 * manual pause tears the GPS stream down, so a runner who pauses, walks 400 m to a shop and
 * resumes leaves one long leg whose average speed clears the moving threshold easily — and
 * counting it would put moving time *above* the run's own clock.
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
        // moving. A leg spanning a break in the recording is rest no matter how fast it looks.
        val isMoving = legMs <= TRACK_BREAK_MS &&
            legMeters / (legMs / 1000.0) > MOVING_SPEED_THRESHOLD_MPS
        if (isMoving) {
            movingMs += legMs + keptFrom(slowSpellMs)
            slowSpellMs = 0L
        } else {
            slowSpellMs += legMs
        }
    }

    // A run that ended slowly - stood at the finish line getting a breath back before pressing
    // stop - closes on an unresolved slow spell, judged by the same rule.
    return (movingMs + keptFrom(slowSpellMs)) / 1000
}

/** A slow spell counts as moving until it outlasts [REST_SUSTAINED_MS]; after that, none of it does. */
private fun keptFrom(slowSpellMs: Long): Long = if (slowSpellMs > REST_SUSTAINED_MS) 0L else slowSpellMs
