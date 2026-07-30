package com.example.runningapp.run

import com.example.runningapp.RunType
import com.example.runningapp.ZoneSeconds

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
    /**
     * The kind of work this Run was, from the Workout it followed — null when it followed none.
     *
     * It is what decides whether the coach looks at the Run at all (#176), so it travels with the
     * totals rather than being read back off the row: a recorded Run has no Run Type column, and
     * re-deriving one from the plan afterwards would answer for whatever the settings say *now*.
     */
    val runType: RunType?,
    val averageBpm: Int,
    val maxBpm: Int,
    val zoneSeconds: ZoneSeconds,
    val noDataSeconds: Long,
    /** How many times the coach sent the runner walking because their heart rate was high. */
    val walkBreaks: Int,
) {
    /** Whether the Run followed a Workout — the record's own flag, and nothing more than that. */
    val isRunWalkMode: Boolean get() = runType != null
}

/**
 * One second of a Run, as it is to be saved.
 *
 * The columns the Run knows about, and no others: the row's own id and the Run's arrive with
 * [RunEffect.SaveHrSample], and the pace comes from GPS, which the Run starts and stops but does
 * not read. The service fills those in as it maps this onto its database entity.
 */
data class HrSampleReading(
    val elapsedSeconds: Long,
    // The wall clock of the second being banked, counted along the Run's own tick rather than read
    // from the clock when the row is written — a late pulse catching up on five seconds banks five
    // readings, and each belongs to the second it counted, not to the moment the phone woke (#84).
    val atMillis: Long,
    val rawBpm: Int,
    val smoothedBpm: Int,
    val connectionStatus: String,
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

    /**
     * Write one completed run Interval's numbers against the Run's row.
     *
     * Emitted as each Interval ends rather than as one batch when the Run finishes, so a Run that
     * dies mid-workout still has everything it got through on disk. Like finalization, it needs an
     * id, so an Interval that ends before the id arrives is held rather than dropped.
     */
    data class SaveIntervalStat(
        val runRowId: Long,
        val stat: IntervalStat,
    ) : RunEffect

    /**
     * Write one second of heart rate against the Run's row.
     *
     * Only seconds that had a reading produce one: a no-data second is counted in the Run's totals
     * but writes no sample, because a zero would add nothing to the zone recompute and would drag
     * the detail chart's axis down to it.
     */
    data class SaveHrSample(
        val runRowId: Long,
        val sample: HrSampleReading,
    ) : RunEffect

    /** Say this out loud. */
    data class Speak(val text: String) : RunEffect

    /** Put this on the Run's notification. */
    data class Notify(val text: String) : RunEffect

    /** Start location updates for this Run. */
    data object StartGps : RunEffect

    /** Stop location updates. */
    data object StopGps : RunEffect

    /**
     * Let go of the sensor and audio session: the Run has ended and needs neither.
     *
     * Emitted whenever the Run finishes, including when it ends *itself* — the cool-down timer
     * reaching zero or a skip out of cool-down — where nothing calls back in to stop it. The
     * button and notification STOP release these directly too, so a Run ended that way sees an
     * idempotent second release; the strap and audio session are #128's and both no-op the repeat.
     * Symmetric with [StopGps]: the Run does not own the strap, it signals that it is done with it.
     */
    data object ReleaseStrap : RunEffect
}

