package com.example.runningapp

/**
 * One condition, two renderings (#109) — the ladder's other half.
 *
 * The ladder ([CueLadderState]) decides *when* the app speaks; this decides *what it says*, on both
 * channels at once. A coaching condition is decided once and rendered side by side: [screenAction]
 * is what the glanceable live screen appends to the zone name ("Tempo — ease off"); [spoken] is the
 * full sentence TTS reads aloud. They live in the same row so a condition can never gain a screen
 * wording without a spoken one, or a spoken one without a screen wording.
 *
 * `voiceStyle` — the old "short"/"detailed" radio — is gone. It asked the user to pick one register
 * for both channels when each channel already has its own right answer, and they differ: short is
 * what a screen wants (glanceable, no full stop, room for the zone name); detailed is what a voice
 * wants (a spoken sentence). It was a channel distinction misfiled as a preference.
 *
 * No [spoken] string names a zone. The screen reports state and can afford the zone name; the voice
 * requests a change, and how far above target you are does not change the words (#108 deleted the
 * severity threshold for exactly this reason), so naming the zone aloud would only smuggle it back.
 */
data class CoachingCue(val screenAction: String, val spoken: String?)

/** A coaching condition, decided once from heart rate and rendered twice by [coachingCue]. */
enum class CueCondition {
    /** Above target and drifting up (past 20 min, still within baseline + 12). */
    ABOVE_DRIFTING,

    /** Above target, plainly. */
    ABOVE,

    /** Below target. */
    BELOW,

    /** On target — the screen says so; the voice stays quiet. */
    ON_TARGET,

    /** Back inside after a spoken cue — the closing bracket the ladder asks for. */
    RETURNED
}

/** The one picker: a condition to its screen action and its spoken sentence. */
fun coachingCue(condition: CueCondition): CoachingCue = when (condition) {
    CueCondition.ABOVE_DRIFTING -> CoachingCue(
        screenAction = "ease off",
        spoken = "Heart rate drifting up. Keep effort steady, or take a short walk break."
    )
    CueCondition.ABOVE -> CoachingCue(
        screenAction = "ease off",
        spoken = "Ease off slightly."
    )
    CueCondition.BELOW -> CoachingCue(
        screenAction = "pick it up",
        spoken = "Gently increase pace."
    )
    CueCondition.ON_TARGET -> CoachingCue(
        screenAction = "on target",
        spoken = null
    )
    CueCondition.RETURNED -> CoachingCue(
        screenAction = "on target",
        spoken = "Back on target."
    )
}

/**
 * Which above-target sentence to speak: drift, or the plain ease-off. Drift is a heart rate creeping
 * up on a steady effort late in a run (past 20 min, still within [baselineHr] + 12); anything higher
 * than that is real overexertion and gets the plain cue.
 *
 * A structured Run used to get a third sentence here that ordered a walk break, and no longer does
 * (#167). Whether a Run follows a Workout says nothing about how the runner feels, and the order was
 * wrong on all three Run Types — see [ADR 0003](docs/adr/0003-heart-rate-is-a-readout-not-a-gate.md).
 * What is left asks for a change of effort, on every Run alike.
 */
fun highCueCondition(secondsRunning: Long, baselineHr: Int?, avgBpm: Int): CueCondition {
    val drifting = secondsRunning > 1200 && baselineHr != null && avgBpm <= baselineHr + 12
    return if (drifting) CueCondition.ABOVE_DRIFTING else CueCondition.ABOVE
}

/**
 * The live screen's zone line: the zone you are actually in, then the action relative to target —
 * "Tempo — ease off". [zoneName] is null (and the band UNKNOWN) when there is no signal, which
 * renders as a plain dash. The band, not the zone, picks the action, so the words and the
 * target-relative colour always agree.
 */
fun liveZoneStatus(zoneName: String?, band: ZoneBand): String {
    val action = when (band) {
        ZoneBand.ABOVE -> coachingCue(CueCondition.ABOVE).screenAction
        ZoneBand.BELOW -> coachingCue(CueCondition.BELOW).screenAction
        ZoneBand.IN -> coachingCue(CueCondition.ON_TARGET).screenAction
        ZoneBand.UNKNOWN -> null
    }
    return if (zoneName == null || action == null) "—" else "$zoneName — $action"
}
