package com.example.runningapp.data

/**
 * A Run's heart rates on the wall clock, which is the only axis they share with its track.
 *
 * A sample's [HrSample.elapsedSeconds] counts *running* seconds, so it stands still through a Pause
 * while a [TrackPoint]'s timestamp does not. Rows written before v16 have no stamp of their own and
 * elapsed seconds stand in, landing late by the length of any Pause before them. That is accepted
 * deliberately: every Run in the history this shipped against is a legacy one, most of them paused,
 * and a heart-rate trace carrying a known bounded offset is worth more to a runner than none. Runs
 * recorded from v16 on carry the wall clock themselves and are exact, so this fades with the old
 * rows rather than living on.
 *
 * The raw reading, not the smoothed one
 * ([ADR 0011](docs/adr/0011-the-smoothed-reading-belongs-to-the-strap.md)): the smoothed number is a
 * coaching aid, and averaging an average would only flatten the Run into something it wasn't.
 *
 * A reading of zero is the absence of one, and is left out rather than reported as a heart that
 * stopped.
 *
 * One function, low enough down that everything which puts a heart rate against a moment can call
 * it: the splits table ([com.example.runningapp.analysis.groundOf]), the route map, and both Exports
 * (#84, #218). Written out per caller they would drift, and one Run would carry a reading at one
 * second on its page and at another in its file.
 */
fun heartRatesByWallSecond(run: RunnerSession, samples: List<HrSample>): Map<Long, Int> =
    samples.filter { it.rawBpm > 0 }.associate { sample ->
        val atMillis = sample.timestampMillis ?: (run.startTime + sample.elapsedSeconds * 1000)
        atMillis / 1000 to sample.rawBpm
    }
