package com.example.runningapp.run

/**
 * The numbers a finished Run is saved with.
 *
 * They come from the module, which watched them accrue, rather than from reading the row back and
 * patching it — so the saved totals are by construction the numbers the runner was being shown.
 */
data class RunTotals(
    val durationSeconds: Long,
    val pausedSeconds: Long,
    val endedAtMillis: Long,
    /** Whether the Run followed a Workout. Drives the record's flag and whether the coach looks. */
    val isRunWalkMode: Boolean,
)

/**
 * Something for the service to do on the Run's behalf.
 *
 * Effects are values, returned in the order they are to be performed. The Run never speaks,
 * writes, notifies or touches Android itself; the service maps each of these to one call and has
 * no decision of its own to make. Returning them rather than firing them mid-calculation is what
 * makes "what happened this second, in what order" a single object a test can assert on.
 */
sealed interface RunEffect {

    /**
     * Insert the Run's row and post its id back as [RunEvent.RunRowCreated].
     *
     * Emitted exactly once per Run, by START and by nothing else.
     */
    data class CreateRunRow(
        val startedAtMillis: Long,
        val targetZoneNumber: Int,
        val runModeSettingValue: String,
        val includeInAiTraining: Boolean,
    ) : RunEffect

    /** Write the finished Run's totals to its row. Emitted once per Run, and only with an id. */
    data class FinalizeRun(
        val runRowId: Long,
        val totals: RunTotals,
    ) : RunEffect

    /** Say this out loud. */
    data class Speak(val text: String) : RunEffect

    /** Put this on the Run's notification. */
    data class Notify(val text: String) : RunEffect

    /** Start location updates for this Run. */
    data object StartGps : RunEffect

    /** Stop location updates. */
    data object StopGps : RunEffect
}

