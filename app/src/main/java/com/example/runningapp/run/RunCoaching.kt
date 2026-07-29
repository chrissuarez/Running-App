package com.example.runningapp.run

import com.example.runningapp.CueLadderState
import com.example.runningapp.ZoneBand

/**
 * How long an unplanned Run coaches nothing at all.
 *
 * A Run with no Workout has no warm-up to be silent through, so it makes its own: five minutes to
 * settle into a pace before the coach starts asking for one.
 */
const val UNPLANNED_GRACE_SECONDS = 300L

/** The second of a Run at which the coach pins the heart rate it later calls drift against. */
const val DRIFT_BASELINE_SECOND = 600L

/**
 * What the coach is holding between samples.
 *
 * The band and the ladder are thrown away and started again at each run Interval's boundary — see
 * [Run]'s `beginRunInterval`. The baseline is the Run's rather than the Interval's, and stays.
 */
data class RunCoaching(
    /** Where the smoothed reading sits relative to target, with the midpoint hysteresis applied. */
    val band: ZoneBand = ZoneBand.UNKNOWN,
    val ladder: CueLadderState = CueLadderState(),
    /**
     * The heart rate at ten minutes, which "drifting up" is measured against. Null until then, and
     * null for a Run that had no reading at that second.
     */
    val baselineHr: Int? = null,
) {
    /** A fresh Interval, and so a fresh coach: nothing carried in from the one before it. */
    fun startAgain(): RunCoaching = copy(band = ZoneBand.UNKNOWN, ladder = CueLadderState())
}

/**
 * The Trigger of the run Interval in progress: whether the Run's heart rate has sat outside target
 * long enough for the coach to speak, and how far in it first did.
 *
 * This was a `WalkDecision`, and it held *why* the walk that follows was taken — heart rate could
 * claim the walk as its own. Nothing claims a walk now (#167): every walk in a Workout is the walk
 * the Workout prescribed, so what is left is the readout alone, which is exactly what CONTEXT.md
 * means by a Trigger — a record of where heart rate went, and never a verdict on the runner. The
 * live screen marks the second on the interval timeline, and [IntervalTracker] saves it with the
 * Interval.
 *
 * Forgotten at each run Interval's start and again when the Workout's last Interval is behind the
 * Run, so one Interval's high heart rate is never shown against the next one.
 */
data class Trigger(
    /** Whether the runner's heart rate went above target during the run Interval in progress. */
    val occurred: Boolean = false,
    /** How far into that Interval it first did. */
    val atSecond: Int? = null,
) {
    /**
     * The coach spoke about a heart rate above target, [secondIntoInterval] seconds in. The first
     * such second is the one kept — later ones in the same Interval do not overwrite it.
     */
    fun triggered(secondIntoInterval: Int): Trigger =
        if (occurred) this else Trigger(occurred = true, atSecond = secondIntoInterval)
}
