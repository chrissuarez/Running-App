package com.example.runningapp.run

/**
 * Everything that can happen to a Run.
 *
 * Bluetooth callbacks, GPS callbacks, taps and the per-second tick all reach the Run this way and
 * only this way, so there is a single ordering of everything that happens to it.
 *
 * Every event carries [nowMillis]: the Run has no clock of its own. That is what makes a Run
 * exercisable in milliseconds on the JVM, and what lets a recorded Run be replayed later.
 */
sealed interface RunEvent {
    val nowMillis: Long

    /** START. Carries the settings pinned for the whole Run — see [RunConfig]. */
    data class Started(
        val config: RunConfig,
        val controls: RunControls,
        override val nowMillis: Long,
    ) : RunEvent

    /**
     * The answer to [RunEffect.CreateRunRow]. A pure module cannot await Room, so the id comes
     * back as an event, and everything held for it flushes here.
     *
     * It says which Run it is the id of, and not only what the id is (#365). An id is addressed
     * because the inbox it travels on outlives the Run that asked for it: a stop and a start inside
     * one insert's window put a second Run in front of the first Run's answer, and a Run that read
     * only the number would take an id belonging to a Run that is not it — two Runs' seconds on one
     * row, and the second Run's own row left empty. The Run's start time is what addresses it: it is
     * pinned for the whole Run, it already travels out on [RunEffect.CreateRunRow], and it is the
     * one thing about a Run that no later event can move.
     */
    data class RunRowCreated(
        val runRowId: Long,
        /** The [RunState.startedAtMillis] of the Run this id belongs to. */
        val forRunStartedAtMillis: Long,
        override val nowMillis: Long,
    ) : RunEvent

    /**
     * The row landed, and the Run's held work is already somebody else's to deliver (#360).
     *
     * The buffer a Run keeps for its id has two sides that can hand it over: this inbox, the moment
     * [RunRowCreated] reaches it, and a service teardown, which must be able to — a teardown can
     * arrive while the insert is still in flight and it quits this inbox behind it, so an id that
     * arrives afterwards reaches nobody. Exactly one of the two may deliver, or the Run's every
     * second is written down twice.
     *
     * So the side that is about to deliver takes the buffer first, atomically, and the side that
     * did not is told so by this. It says the same thing [RunRowCreated] says — the row exists and
     * here is its number — and asks for the one thing that is different: let the held work go
     * without emitting it. It is only ever sent by a teardown that took the buffer, so the Run
     * starts nothing on it either; there would be no service left to stop what it started.
     */
    data class HeldWorkTakenOver(
        val runRowId: Long,
        /** The [RunState.startedAtMillis] of the Run this id belongs to — see [RunRowCreated]. */
        val forRunStartedAtMillis: Long,
        override val nowMillis: Long,
    ) : RunEvent

    /** The per-second pulse. The Run reads the time on it, not the fact that it arrived. */
    data class Tick(override val nowMillis: Long) : RunEvent

    /**
     * A reading arrived from the Strap.
     *
     * Separate from the per-second [Tick] because it is: a Strap sends when it sends, and a Run
     * banks a second whether one arrived or not. [connectionStatus] rides along because it is what
     * a saved sample records the second as having been taken under.
     */
    data class HeartRateSampled(
        val bpm: Int,
        val connectionStatus: String,
        override val nowMillis: Long,
    ) : RunEvent

    /**
     * The Strap went away (#110).
     *
     * Not a Run ending, and not a pause — the clock keeps running and the seconds bank as no-data.
     * It arrives as its own event rather than as a reading of zero so that a dropout can never be
     * mistaken for a packet the coach should reason about.
     */
    data class HeartRateLost(
        val connectionStatus: String,
        override val nowMillis: Long,
    ) : RunEvent

    /** The runner changed a live control mid-Run. */
    data class ControlsChanged(
        val controls: RunControls,
        override val nowMillis: Long,
    ) : RunEvent

    /** The pause/resume button, which is one control and so asks for whichever it is not. */
    data class PauseToggled(override val nowMillis: Long) : RunEvent

    /**
     * The notification's Pause action, which is one of two buttons rather than a toggle.
     *
     * Separate from [PauseToggled] because the shade lags the Run: the Resume button can still be
     * on screen after the Run resumed itself, and tapping it must do nothing rather than pause a
     * Run the runner is watching. Asking for a named direction is what makes that impossible —
     * a published-status check at the call site would be racing the very lag it is guarding.
     */
    data class PauseRequested(override val nowMillis: Long) : RunEvent

    /** The notification's Resume action. See [PauseRequested]. */
    data class ResumeRequested(override val nowMillis: Long) : RunEvent

    /** A sustained standstill was detected (#39). */
    data class AutoPauseRequested(override val nowMillis: Long) : RunEvent

    /** Movement resumed after an auto-pause (#39). */
    data class AutoResumeRequested(override val nowMillis: Long) : RunEvent

    /** The skip button: warm-up hands over to main, main to cool-down, cool-down ends the Run. */
    data class PhaseSkipped(override val nowMillis: Long) : RunEvent

    /** STOP, from wherever it came — the button, the notification, or the cool-down finishing. */
    data class Stopped(override val nowMillis: Long) : RunEvent
}
