package com.example.runningapp.data

import com.example.runningapp.HrProfile
import com.example.runningapp.tallyZoneSeconds
import com.example.runningapp.training.effortScoreOf

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
 *    than to-the-metre what a completed Run would have stored. It is measured by the one rule every
 *    path measures a track by ([measureTrackDistanceKm]), so a rescued Run does not shrink relative
 *    to the same Run finished live (#204).
 *  - **Walk breaks** are the Workout's, not the recording's. What is banked is the Interval that
 *    ended, and the walk that follows it is the Workout's answer to that — counted as the Run takes
 *    it and gone with the Run. The row keeps its own value rather than being handed a count derived
 *    from how many Intervals happen to have been banked.
 *
 * The **run/walk flag** is neither derived nor left alone but *found*: `run_walk_interval_stats`
 * holds a row per Interval the Run banked as it ran, and those rows are written only by a Run
 * actually running Intervals. Their presence is the same evidence [MIGRATION_13_14] treats as the
 * one durable signal of a structured Run, read the same way here. It can only turn the flag on — a
 * Run killed during its warm-up has banked no Interval and has nothing to say either way, which is
 * the case that migration calls having no interval evidence to preserve.
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
 * recovery path should never be the thing that puts something into history. Nothing at all is the
 * test, not any particular number of rows: a single fix minutes after START is one row and it proves
 * both that the Run was recording and how far into it that was.
 *
 * Two tracks, because *when* and *where* want different evidence. A fix too vague to draw is still
 * proof the Run was being recorded at that moment, so the clock and the end of the Run are read from
 * every fix the Run wrote down, while distance and the start pin are read from only the fixes good
 * enough to trust. Given one track for both, a Run that lost reception and never got it back before
 * the process died would stop at its last good fix — and one that never got a good fix at all would
 * not be rescued, though the database can plainly see it running.
 *
 * @param samples the Run's heart-rate samples, in any order.
 * @param track every track point the Run recorded, in any order — the unfiltered record of when it
 *   was running.
 * @param mappedTrack the same points after the accuracy gate
 *   ([SessionRepository.getTrackPointsForMap]), so a wild fix the Run itself refused cannot reappear
 *   here as a phantom sprint.
 * @param bankedIntervals whether the Run banked any run/walk Interval before it was killed.
 */
fun RunnerSession.finishedFromRecord(
    samples: List<HrSample>,
    track: List<TrackPoint>,
    mappedTrack: List<TrackPoint>,
    profile: HrProfile,
    bankedIntervals: Boolean = false,
): RunnerSession? {
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
    // Nothing to put back: no reading was banked and nothing the track holds says the Run ever got
    // past its first instant.
    if (samples.isEmpty() && durationSeconds == 0L) return null

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
    val distanceKm = measureTrackDistanceKm(mappedTrack)
    val firstFix = mappedTrack.minByOrNull { it.timestampMillis }

    return copy(
        endTime = endedAtMillis,
        durationSeconds = durationSeconds,
        avgBpm = if (bpms.isEmpty()) 0 else (bpms.sumOf { it.toLong() } / bpms.size).toInt(),
        maxBpm = bpms.maxOrNull() ?: 0,
        distanceKm = distanceKm,
        avgPaceMinPerKm = averagePaceMinPerKm(durationSeconds, distanceKm),
        noDataSeconds = noDataSeconds,
        // Never taken away: the flag is a claim the row can only have got from a Run that made it,
        // and a banked Interval is the same claim from the other direction.
        isRunWalkMode = isRunWalkMode || bankedIntervals,
        zone1Seconds = zones.zone1,
        zone2Seconds = zones.zone2,
        zone3Seconds = zones.zone3,
        zone4Seconds = zones.zone4,
        zone5Seconds = zones.zone5,
        // Scored from the same beats and the same profile as the zones above, so a rescued Run
        // carries the number a finished one would have banked as it ran (#61). A Run whose samples
        // are all gone gets null rather than a zero — it recorded no heart rate, so there is
        // nothing to say about what it cost.
        effortScore = effortScoreOf(bpms, profile),
        // Only where the Run has none. A rescued Run is stamped once, at the moment it is rescued;
        // re-running the pass must not overwrite a start position that is already there.
        startLatitude = startLatitude ?: firstFix?.latitude,
        startLongitude = startLongitude ?: firstFix?.longitude,
        // Settled, so nothing puts this Run to the Plan afterwards (#297). It reached no finish and
        // no finish sheet, and its runner was never asked what it was — so a graduation granted off
        // it a launch later would be the app deciding a Stage on a Run nobody ever closed. That is
        // the pass over history the graduation rule refuses to make (ADR 0016), and it is also what
        // this Run has always got: nothing here ever settled a Stage. Said here rather than left to
        // the default so a rescue cannot race the launch pass for the same row.
        //
        // **A Run nobody closed is what this can see, not what is always true.** Since the row
        // became the settlers' mutual exclusion ([SETTLE_RUN_ROW_IF_UNSETTLED]), a rescue can win
        // the row of a Run the runner deliberately stopped, whose own finalize was still in flight
        // — and nothing here or in the record says so. The mark goes on all the same, because it is
        // the right answer for every Run this can tell apart and the wrong one would grant
        // graduations off Runs nobody closed. The Run's own finalize is what corrects it, from the
        // one place that knows: it is told it lost the row and hands the question back
        // ([HAND_THE_STAGE_QUESTION_BACK], #383).
        stageSettled = true,
    )
}

/**
 * Running seconds a stored track can vouch for: the wall clock from the Run's start to its last fix,
 * less the spells the recording was down.
 *
 * The head — [startTime] to the first fix — counts. The Run's clock starts at START, and the wait
 * for a first satellite fix is time the runner spent running rather than time they spent paused.
 * Unless the first fix says otherwise: a fix carrying [TrackPoint.startsAfterPause] is the far side
 * of a pause, and a pause before the track began means the wait was not all running. What the Run
 * did manage to run before it stopped is unknowable from the track and is given up rather than
 * guessed at — the samples still speak for it, and a head counted through a pause could not be
 * argued down by them, because the clock is whichever record reaches further.
 *
 * That last rule was right and, until #195, unreachable: the recorder never wrote the marker onto a
 * Run's opening fix, so a pause before the first fix left nothing behind to find it by and the whole
 * pause was credited as the wait. The recorder now tells a Run's beginning from a Run's resume
 * ([com.example.runningapp.recording.PauseMark]), which is what makes this the rule it always should
 * have been rather than a guess at a threshold.
 *
 * Everything after the head is measured leg by leg, skipping only the legs that cross a *recorded*
 * pause: the Run's clock stops for a pause, so those seconds were never its to count.
 *
 * A pause and only a pause, which is where this parts company with [measureMovingTimeSeconds] — it
 * treats an Outage as a break too, and here that would be wrong twice over. A gap in the fixes is
 * not evidence of a stop: GPS loses the sky in a tunnel or a stairwell, and
 * [SessionRepository.getTrackPointsForMap] drops every fix too vague to trust, so a patch of poor
 * reception arrives here as a hole. Meanwhile a real pause needs no guessing at — every one of them,
 * held down or automatic, is written onto the fix that resumed the Run
 * ([TrackPoint.startsAfterPause]). Those two together are the difference between the questions:
 * time across an Outage is time that passed with the Run still counting it.
 *
 * This is a floor rather than the Run's clock exactly — the seconds after the last fix are gone with
 * the process that would have written them. It exists so a Run whose samples stop early, or never
 * start, is not rescued as a Run that lasted no time at all.
 */
fun measureTrackRecordedSeconds(startTime: Long, points: List<TrackPoint>): Long {
    val ordered = points.sortedBy { it.timestampMillis }
    val firstFix = ordered.firstOrNull() ?: return 0L
    var millis =
        if (firstFix.startsAfterPause) 0L else (firstFix.timestampMillis - startTime).coerceAtLeast(0L)
    for (i in 1 until ordered.size) {
        val legMs = ordered[i].timestampMillis - ordered[i - 1].timestampMillis
        if (legMs <= 0 || ordered[i].startsAfterPause) continue
        millis += legMs
    }
    return millis / 1000
}

/**
 * The ground a stored track covers, in kilometres — the sum of what its legs carry.
 *
 * One walk of the track and one rule about what a leg is worth ([measureTrack]), rather than a second
 * opinion written here: a Run rescued from its record must report the distance the same Run finished
 * live would have banked, and the only way two paths are certain to agree is to be one path (#204).
 * So an Outage carries its straight line, a Pause carries nothing, and the Splits cut from these same
 * legs add up to the figure this returns.
 */
fun measureTrackDistanceKm(points: List<TrackPoint>): Double =
    measureTrack(points).legs.sumOf { it.meters } / 1000.0
