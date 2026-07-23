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

/** Why the runner is walking. Recorded per Interval, and shown live so the walk is never a mystery. */
enum class WalkReason(val label: String) {
    PLANNED("Planned"),
    HR_TRIGGERED("HR-triggered"),
}

/**
 * Why the walk that follows this run Interval was taken, decided while the run Interval is still
 * going.
 *
 * Forgotten at each run Interval's start and again when the Workout's last Interval is behind the
 * Run, so one Interval's high heart rate can never explain the next one's walk.
 */
data class WalkDecision(
    val reason: WalkReason = WalkReason.PLANNED,
    /** Whether the runner's heart rate went above target during the run Interval in progress. */
    val hrCapExceededInInterval: Boolean = false,
    /** How far into that Interval it first did. */
    val hrCapExceededAtSecond: Int? = null,
) {
    /**
     * The runner was sent walking by their heart rate, [secondIntoInterval] seconds in.
     *
     * Settled here rather than at the handover because the runner is being asked to walk now, and
     * the live screen has to be able to say why now.
     */
    fun triggered(secondIntoInterval: Int): WalkDecision =
        if (hrCapExceededInInterval) copy(reason = WalkReason.HR_TRIGGERED)
        else WalkDecision(
            reason = WalkReason.HR_TRIGGERED,
            hrCapExceededInInterval = true,
            hrCapExceededAtSecond = secondIntoInterval,
        )

    /** The run Interval reached its prescribed end: the walk is whatever the Interval made it. */
    fun atHandover(): WalkDecision =
        copy(reason = if (hrCapExceededInInterval) WalkReason.HR_TRIGGERED else WalkReason.PLANNED)
}
