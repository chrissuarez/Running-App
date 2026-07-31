package com.example.runningapp.data

import com.example.runningapp.HrProfile
import com.example.runningapp.recording.geodesicDistanceMeters
import com.example.runningapp.tallyZoneSeconds

/**
 * Rebuilds the totals of a Run that stopped without finishing, from what it managed to write down
 * before it did (#192).
 *
 * A Run's row is inserted at START and stamped with its totals at STOP, so anything that ends the
 * process in between — the system reclaiming memory mid-run, a crash, a battery pull — leaves a row
 * with `endTime = 0`. Every query in the app reads that as "still being recorded" and steps around
 * it, so the Run vanishes: no history entry, no export, nothing for the coach. The seconds
 * themselves are not lost, though. They were written to `hr_samples` and `track_points` as they
 * happened, one row per second, which is enough to derive every total the finish would have written.
 *
 * Derived rather than estimated. The Run banks its heart-rate tally from the same `rawBpm` it saves
 * on the sample, so an average, a maximum and a zone breakdown rebuilt from the stored samples are
 * the numbers the finish would have written, not an approximation of them. Two totals cannot be:
 *
 *  - **Distance** is measured from the stored track rather than replayed through the recorder's live
 *    accumulator, so it is the distance the map and the GPX export already draw for this Run rather
 *    than to-the-metre what a completed Run would have stored. Legs across a break do not count, for
 *    the reason [measureMovingTimeSeconds] gives: a pause is not ground the runner covered.
 *  - **Walk breaks and the run/walk flag** are the Workout's, not the recording's, and nothing in
 *    the record says which Workout was being run. They keep the row's own values.
 *
 * Zone seconds are tallied against the profile passed in rather than whatever was pinned at START,
 * which is the same choice the #112 re-tally makes for history — and it must be passed the same
 * profile that re-tally uses, so a rescued Run bands like the neighbours it lands among. See
 * [SessionRepository.rescueInterruptedRuns] for which profile that is and what keeps the two passes
 * from disagreeing.
 *
 * Returns null when there is nothing to rebuild from: a Run that recorded no second at all, which
 * is a START that died before its first reading. Such a row is left exactly as it is rather than
 * being finished as a zero-second Run, because a Run with nothing in it is not a Run — and a
 * recovery path should never be the thing that puts something into history.
 *
 * @param samples the Run's heart-rate samples, in any order.
 * @param track the Run's track points, already accuracy-filtered by
 *   [SessionRepository.getTrackPointsForMap] so a wild fix the Run itself refused cannot reappear
 *   here as a phantom sprint.
 */
fun RunnerSession.finishedFromRecord(
    samples: List<HrSample>,
    track: List<TrackPoint>,
    profile: HrProfile,
): RunnerSession? {
    if (samples.isEmpty() && track.size < 2) return null

    // The Run's clock, which counts *running* seconds and so already excludes anything it spent
    // paused. The last second banked rather than the number of samples: a dropout saves no row, and
    // counting rows would hand those seconds back and shorten the Run. And the track as well as the
    // samples, because the samples are not always the longer record — a Run recorded without a strap
    // banks no second at all, and a strap that drops out for good banks its last one early, while
    // the track goes on being written either way. Whichever record reaches further is the Run.
    val durationSeconds = maxOf(
        samples.maxOfOrNull { it.elapsedSeconds } ?: 0L,
        measureTrackRecordedSeconds(startTime, track),
    )
    // Seconds the Run counted but had no reading for — exactly the ones with no sample row, which
    // is how the Run itself counts them.
    val noDataSeconds = (durationSeconds - samples.size).coerceAtLeast(0L)

    // Wall clock of the last thing recorded: when the recording died, which is the closest thing
    // there is to when the Run ended. Falls back to the Run's own clock for samples written before
    // v16, which carry no timestamp.
    val endedAtMillis = maxOf(
        samples.mapNotNull { it.timestampMillis }.maxOrNull() ?: 0L,
        track.maxOfOrNull { it.timestampMillis } ?: 0L,
    ).takeIf { it > startTime } ?: (startTime + durationSeconds * 1000L)

    val bpms = samples.map { it.rawBpm }
    val zones = tallyZoneSeconds(bpms, profile)
    val distanceKm = measureTrackDistanceKm(track)
    val firstFix = track.minByOrNull { it.timestampMillis }

    return copy(
        endTime = endedAtMillis,
        durationSeconds = durationSeconds,
        avgBpm = if (bpms.isEmpty()) 0 else (bpms.sumOf { it.toLong() } / bpms.size).toInt(),
        maxBpm = bpms.maxOrNull() ?: 0,
        distanceKm = distanceKm,
        avgPaceMinPerKm = averagePaceMinPerKm(durationSeconds, distanceKm),
        noDataSeconds = noDataSeconds,
        zone1Seconds = zones.zone1,
        zone2Seconds = zones.zone2,
        zone3Seconds = zones.zone3,
        zone4Seconds = zones.zone4,
        zone5Seconds = zones.zone5,
        // Only where the Run has none. A rescued Run is stamped once, at the moment it is rescued;
        // re-running the pass must not overwrite a start position that is already there.
        startLatitude = startLatitude ?: firstFix?.latitude,
        startLongitude = startLongitude ?: firstFix?.longitude,
    )
}

/**
 * Running seconds a stored track can vouch for: the wall clock from the Run's start to its last fix,
 * less the spells the recording was down.
 *
 * The head — [startTime] to the first fix — counts. The Run's clock starts at START, and the wait
 * for a first satellite fix is time the runner spent running rather than time they spent paused.
 * Everything after it is measured leg by leg, skipping only the legs that cross a *recorded* pause:
 * the Run's clock stops for a pause, so those seconds were never its to count.
 *
 * A pause and only a pause, which is where this parts company with [measureTrackDistanceKm] and
 * [measureMovingTimeSeconds] — they treat a long gap between fixes as a break too, and here that
 * would be wrong twice over. A gap is not evidence of a stop: GPS loses the sky in a tunnel or a
 * stairwell, and [SessionRepository.getTrackPointsForMap] drops every fix too vague to trust, so a
 * patch of poor reception arrives here as a hole. Meanwhile a real pause needs no guessing at —
 * every one of them, held down or automatic, is written onto the fix that resumed the Run
 * ([TrackPoint.startsAfterPause]). Those two together are the difference between the questions:
 * distance across a gap is ground nothing witnessed the runner cover, but time across a gap is time
 * that passed with the Run still counting it.
 *
 * This is a floor rather than the Run's clock exactly — the seconds after the last fix are gone with
 * the process that would have written them. It exists so a Run whose samples stop early, or never
 * start, is not rescued as a Run that lasted no time at all.
 */
fun measureTrackRecordedSeconds(startTime: Long, points: List<TrackPoint>): Long {
    val ordered = points.sortedBy { it.timestampMillis }
    val firstFix = ordered.firstOrNull() ?: return 0L
    var millis = (firstFix.timestampMillis - startTime).coerceAtLeast(0L)
    for (i in 1 until ordered.size) {
        val legMs = ordered[i].timestampMillis - ordered[i - 1].timestampMillis
        if (legMs <= 0 || ordered[i].startsAfterPause) continue
        millis += legMs
    }
    return millis / 1000
}

/**
 * The ground a stored track covers, in kilometres — the sum of its legs, minus the ones that span a
 * break in the recording.
 *
 * The same points and the same break rule as [measureMovingTimeSeconds], so the two numbers describe
 * one route rather than two. A leg across a pause is not distance for the same reason it is not
 * moving time: the runner may have walked to a shop door and back, and the straight line between the
 * fix before and the fix after is ground nothing witnessed them cover.
 */
fun measureTrackDistanceKm(points: List<TrackPoint>): Double {
    if (points.size < 2) return 0.0
    val ordered = points.sortedBy { it.timestampMillis }
    var meters = 0.0
    for (i in 1 until ordered.size) {
        val previous = ordered[i - 1]
        val current = ordered[i]
        if (current.startsAfterPause) continue
        if (current.timestampMillis - previous.timestampMillis > TRACK_BREAK_MS) continue
        meters += geodesicDistanceMeters(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
        )
    }
    return meters / 1000.0
}
