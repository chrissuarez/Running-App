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
     */
    data class RunRowCreated(
        val runRowId: Long,
        override val nowMillis: Long,
    ) : RunEvent

    /** The per-second pulse. The Run reads the time on it, not the fact that it arrived. */
    data class Tick(override val nowMillis: Long) : RunEvent

    /** The runner changed a live control mid-Run. */
    data class ControlsChanged(
        val controls: RunControls,
        override val nowMillis: Long,
    ) : RunEvent

    /** The pause/resume button. */
    data class PauseToggled(override val nowMillis: Long) : RunEvent

    /** A sustained standstill was detected (#39). */
    data class AutoPauseRequested(override val nowMillis: Long) : RunEvent

    /** Movement resumed after an auto-pause (#39). */
    data class AutoResumeRequested(override val nowMillis: Long) : RunEvent

    /** The skip button: warm-up hands over to main, main to cool-down, cool-down ends the Run. */
    data class PhaseSkipped(override val nowMillis: Long) : RunEvent

    /** STOP, from wherever it came — the button, the notification, or the cool-down finishing. */
    data class Stopped(override val nowMillis: Long) : RunEvent
}
