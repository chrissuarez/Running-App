package com.example.runningapp.run

import com.example.runningapp.CuePriority
import com.example.runningapp.HrProfile
import com.example.runningapp.RunType
import com.example.runningapp.ZoneSeconds

/**
 * A name for a cue the Run may want back before it is spoken, so the service knows which one
 * [RunEffect.WithdrawCue] means. There is one of them, and the Run can have at most one outstanding.
 */
enum class CueTag {
    /** The halfway turnaround (#208), taken back when the Run enters its cool-down. */
    TURNAROUND,
}

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
    /**
     * What the Run cost, Edwards zone-weighted (#61) — null for a Run that read no heart rate at
     * all, which has nothing to score.
     *
     * Carried with the totals rather than worked out from the saved samples afterwards, so the Run
     * is scored under the profile it was recorded and coached under.
     */
    val effortScore: Int?,
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
        /**
         * The Reserve this Run's zone seconds will be banded against, written with the row so the
         * Run remembers it (#228).
         *
         * It goes down at START rather than at the finish because that is when it is pinned, and
         * because a Run whose process is killed never reaches a finish: the rescue pass then has
         * the Run's own numbers to rebuild it from instead of a global that has since moved on.
         */
        val hrProfile: HrProfile,
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

    /**
     * Say this out loud, in its turn.
     *
     * [priority] is where it sits in the queue against everything else waiting (#53); the Run says
     * how urgent its own cue is and nothing more. When and how it is actually said — what is
     * mid-sentence, what else is queued — is the speech layer's business, which the Run cannot ask
     * about (ADR 0002).
     *
     * A [tag] names a cue the Run may later want back — see [WithdrawCue]. Most carry none.
     */
    data class Speak(
        val text: String,
        val priority: CuePriority,
        val tag: CueTag? = null,
    ) : RunEffect

    /**
     * Take back a cue that has not been spoken yet: the Run has changed underneath it (#208).
     *
     * The price of a queue that never drops a cue is that the Run can move on while one waits — the
     * runner turns the turnaround off, or skips into the cool-down — and the thing it was going to
     * say is no longer true. Speaking "turn around" to someone already heading home is worse than
     * losing the cue, so the rule that a cue is never dropped comes with a rule that its producer
     * may take it back.
     *
     * Inert when nothing of that [tag] is waiting, which is most of the time: the Run knows a cue
     * was issued, not whether the speech layer has since spoken it, and it does not need to.
     */
    data class WithdrawCue(val tag: CueTag) : RunEffect

    /** Put this on the Run's notification. */
    data class Notify(val text: String) : RunEffect

    /** Start location updates for this Run. */
    data object StartGps : RunEffect

    /** Stop location updates. */
    data object StopGps : RunEffect

    /**
     * Let go of the sensor: the Run has ended and does not need it.
     *
     * Emitted whenever the Run finishes, including when it ends *itself* — the cool-down timer
     * reaching zero or a skip out of cool-down — where nothing calls back in to stop it. The
     * button and notification STOP release it directly too, so a Run ended that way sees an
     * idempotent second release, which the strap no-ops (#128). Speech is not released here: the
     * queue lets go of audio focus when it drains, whether or not a Run is still on (#53).
     * Symmetric with [StopGps]: the Run does not own the strap, it signals that it is done with it.
     */
    data object ReleaseStrap : RunEffect
}

