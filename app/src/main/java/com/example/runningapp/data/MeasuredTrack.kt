package com.example.runningapp.data

import com.example.runningapp.recording.geodesicDistanceMeters

/**
 * The stretch between two consecutive fixes, judged: how much ground it recorded, and how much of
 * its time counts as the runner moving rather than resting.
 *
 * [meters] is the ground the leg carries, and a leg across a break carries the straight line between
 * the fixes either side of it (#204). The runner did cover that ground — a straight line is never
 * longer than the route they took, so counting it can only under-state a run — and the live recorder
 * banks it as it runs, so a reader that skipped it would have a run's splits fail to add up to the
 * distance printed above them.
 *
 * Two legs carry nothing. A leg across a *pause* carries no ground, because GPS is torn down for the
 * length of one and the runner was not running: the recorder drops its distance baseline there
 * ([com.example.runningapp.recording.SessionRecorder.discardLastFix]) and so must everything else. A
 * leg between two fixes stamped the same moment carries none either — it has no time to have been
 * run in, so counted it would be distance for free.
 *
 * [movingMillis] is the part of the leg that counts towards moving time: all of it, or none.
 */
data class TrackLeg(
    val meters: Double,
    val millis: Long,
    val movingMillis: Long,
    /**
     * Whether the recording says where the runner went between these two fixes.
     *
     * False across a pause and across a gap in the recording — stretches the run went unwitnessed,
     * which anything reading the *shape* of the run has to break at rather than join across: no line
     * is drawn over one, no climb is banked across one, no window of pace or height reaches through
     * one. It is not a claim about distance. A leg may be unrecorded and still carry the straight
     * line the runner covered over it (see [meters]); what it may never do is have that line drawn as
     * if the route were known.
     *
     * True for two fixes stamped the same moment: there is no stretch between them to have missed,
     * and reading that as a break would have a single duplicate timestamp mid-hill throw away the
     * climb banked below it.
     */
    val recorded: Boolean,
)

/**
 * A finished run's track with every leg judged: the fixes in time order, and one [TrackLeg] per gap
 * between them, so `legs[i]` runs from `points[i]` to `points[i + 1]`.
 *
 * One walk, read two ways. Moving time folds the legs into a total ([measureMovingTimeSeconds]) and
 * the splits table cuts them at kilometre boundaries ([com.example.runningapp.analysis.splitsOf]);
 * both have to be measuring the same run the same way, or a run's splits would not add up to its
 * own pace.
 */
data class MeasuredTrack(val points: List<TrackPoint>, val legs: List<TrackLeg>) {
    /**
     * The runs of consecutive legs the recording covers, as ranges of leg index — where a line may
     * be drawn without inventing ground.
     *
     * One rule, in one place, because everything that draws a route has to cut it at the same
     * moments: the route map on a run's page ([com.example.runningapp.analysis.trackMapOf]) and the
     * thumbnail beside it in History ([com.example.runningapp.analysis.routeThumbnailOf]). Written
     * twice they would drift, and the same run would be a loop in one view and an out-and-back in
     * the other.
     *
     * Legs rather than fixes, so a caller that has something to say about each leg — the colour the
     * heart rate makes it, say — can still say it. Stretch `a..b` covers `points[a]` through
     * `points[b + 1]`, so every stretch is at least two fixes and is therefore a line.
     */
    val unbrokenLegs: List<IntRange>
        get() {
            val stretches = mutableListOf<IntRange>()
            var start: Int? = null
            legs.forEachIndexed { i, leg ->
                if (leg.recorded) {
                    if (start == null) start = i
                } else {
                    start?.let { stretches += it..i - 1 }
                    start = null
                }
            }
            start?.let { stretches += it..legs.lastIndex }
            return stretches
        }
}

/**
 * Walks a finished run's track, judging each leg moving or resting the way Strava does for an
 * uploaded GPX (#163).
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
fun measureTrack(points: List<TrackPoint>): MeasuredTrack {
    val ordered = points.sortedBy { it.timestampMillis }
    if (ordered.size < 2) return MeasuredTrack(ordered, emptyList())

    val legs = arrayOfNulls<TrackLeg>(ordered.size - 1)
    // The slow spell currently being accumulated: the legs of it, and how long it has run for. It
    // is only rest once it outlasts REST_SUSTAINED_MS; until then it is still provisionally moving
    // time, held back until a moving leg redeems it or the run ends.
    val slowSpell = mutableListOf<Int>()
    var slowSpellMs = 0L
    var everMoved = false

    for (i in 1 until ordered.size) {
        val previous = ordered[i - 1]
        val current = ordered[i]
        val legMs = current.timestampMillis - previous.timestampMillis
        // Two fixes stamped the same moment leave a leg with no time to have been run in, so it
        // carries no ground either — counted, it would be distance for free.
        if (legMs <= 0) {
            legs[i - 1] = TrackLeg(meters = 0.0, millis = 0L, movingMillis = 0L, recorded = true)
            continue
        }

        val legMeters = geodesicDistanceMeters(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
        )
        // A leg spanning a break in the recording is rest no matter how fast it looks: the run said
        // so on the fix that resumed it, or, for a run recorded before it said so, the gap itself is
        // the only evidence there is.
        val spansPause = current.startsAfterPause
        val spansBreak = spansPause || legMs > TRACK_BREAK_MS
        when {
            // A break is settled rest, not a slow spell waiting to be judged. Banking it as one
            // would hand it back: a two-second recorded pause is shorter than the rest window, so
            // the next moving leg would restore every paused second as moving time. The slow spell
            // running into it goes too - the runner was slowing to the stop, and it is rest for
            // the same reason the stop is.
            //
            // It still carries its ground unless it was a pause, which is the one break the runner
            // was not running across - see [TrackLeg.meters].
            spansBreak -> {
                legs[i - 1] = TrackLeg(
                    meters = if (spansPause) 0.0 else legMeters,
                    millis = legMs,
                    movingMillis = 0L,
                    recorded = false,
                )
                slowSpell.clear()
                slowSpellMs = 0L
            }
            // "Faster than a 30-minute mile", as Strava puts it - so exactly the threshold is not
            // moving.
            legMeters / (legMs / 1000.0) > MOVING_SPEED_THRESHOLD_MPS -> {
                legs[i - 1] = TrackLeg(legMeters, legMs, movingMillis = legMs, recorded = true)
                legs.redeem(slowSpell, slowSpellMs)
                slowSpell.clear()
                slowSpellMs = 0L
                everMoved = true
            }
            else -> {
                legs[i - 1] = TrackLeg(legMeters, legMs, movingMillis = 0L, recorded = true)
                slowSpell += i - 1
                slowSpellMs += legMs
            }
        }
    }

    // A run that ended slowly - stood at the finish line getting a breath back before pressing
    // stop - closes on an unresolved slow spell, judged by the same rule. A run that never cleared
    // the threshold at all keeps nothing: a spell too short to be rest is still not moving on its
    // own, and a run started and stopped by mistake at a standstill should read zero, not two
    // seconds of movement it never made.
    if (everMoved) legs.redeem(slowSpell, slowSpellMs)

    @Suppress("UNCHECKED_CAST")
    return MeasuredTrack(ordered, (legs as Array<TrackLeg>).asList())
}

/** A slow spell counts as moving until it outlasts [REST_SUSTAINED_MS]; after that, none of it does. */
private fun Array<TrackLeg?>.redeem(slowSpell: List<Int>, slowSpellMs: Long) {
    if (slowSpellMs > REST_SUSTAINED_MS) return
    slowSpell.forEach { index ->
        val leg = this[index] ?: return@forEach
        this[index] = leg.copy(movingMillis = leg.millis)
    }
}
