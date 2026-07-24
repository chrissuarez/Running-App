package com.example.runningapp.run

/**
 * Which of a Run's three stretches it is in. Every Run has all three, in this order.
 *
 * The two names a Phase answers to travel with it, so a fourth Phase would not mean hunting for
 * the places that spell them.
 */
enum class RunPhase(
    /** As the coach says it out loud. */
    val spokenName: String,
    /** As the notification labels it. */
    val notificationName: String,
) {
    WARM_UP("warm up", "Warm-up"),
    MAIN("main workout", "Main"),
    COOL_DOWN("cool down", "Cooldown"),
}

/**
 * Where a Run is in its life.
 *
 * [STOPPING] is the one that is easy to miss and the reason the old code needed three cooperating
 * flags: the runner has stopped, but the Run's database row does not exist yet, so there is
 * nothing to finalize against. It ends the moment the id arrives.
 */
enum class RunLifecycle {
    /** No Run. The state a fresh [RunState] starts in and the one a finished Run is left in. */
    IDLE,
    RUNNING,
    PAUSED,

    /** Stopped by the runner (or by the cool-down), waiting only on its row id. */
    STOPPING,
    STOPPED,
    ;

    /** A Run that is still accruing time, or could resume and start again. */
    val isLive: Boolean get() = this == RUNNING || this == PAUSED
}

/**
 * The whole of a Run, as a value.
 *
 * The Run publishes this complete every time rather than a patch, so "which fields changed" stops
 * being bookkeeping anyone can get wrong; the service performs exactly one write of it. Nothing
 * here is mutable and nothing needs a lock — one thread posts events, and this comes back.
 */
data class RunState(
    val lifecycle: RunLifecycle = RunLifecycle.IDLE,

    /** Pinned at START, null when no Run has started. See [RunConfig]. */
    val config: RunConfig? = null,
    val controls: RunControls = RunControls(),

    /**
     * The id of this Run's database row, once it exists.
     *
     * Null is not an error state, it is the first few hundred milliseconds of every Run: the row is
     * created asynchronously and the id comes back as an event. Work that needs an id is held in
     * [pendingRowEffects] until it lands.
     */
    val runRowId: Long? = null,

    val phase: RunPhase = RunPhase.WARM_UP,
    val secondsRunning: Long = 0,
    val secondsPaused: Long = 0,
    val phaseSecondsElapsed: Long = 0,

    /** Whether the runner skipped the warm-up, which stops it being waited for elsewhere. */
    val warmUpSkipped: Boolean = false,

    /**
     * The Interval the Run is in, or null when there is not one: before the Workout's Intervals
     * begin, after they finish, and for the whole of a Run that has no Workout. See [RunIntervals].
     */
    val intervals: RunIntervals? = null,

    /**
     * Whether this Run's Intervals are behind it — the last one completed, or the main Phase
     * skipped past them.
     *
     * Needed because [intervals] is null both before and after, and because finishing the Workout
     * does not on its own move the Run out of the main Phase: without this the next second would
     * start the whole Workout again.
     */
    val intervalsFinished: Boolean = false,

    /**
     * The run Interval being measured, if the Run is in one. Bookkeeping, like [pendingRowEffects]
     * — the screen reads [intervals]. Null through every walk Interval: see [IntervalTracker].
     */
    val intervalTracker: IntervalTracker? = null,

    /** Everything the Run has counted: zone seconds, no-data seconds, and the heart-rate totals. */
    val tally: RunTally = RunTally(),

    /** What the Strap is saying, and the rolling average taken from it. See [RunHeartRate]. */
    val heartRate: RunHeartRate = RunHeartRate(),

    /** What the coach is holding between samples: the band, the ladder, the drift baseline. */
    val coaching: RunCoaching = RunCoaching(),

    /**
     * Why the runner is walking, and whether their heart rate has been above target during the run
     * Interval in progress. Written only by the coach's cue decisions. See [WalkDecision].
     */
    val walkDecision: WalkDecision = WalkDecision(),

    /** How many times this Run's coach has sent the runner walking. Saved with the Run. */
    val walkBreaks: Int = 0,

    /**
     * Whether the current pause was the Run's own doing rather than a tap. Auto-pause reuses
     * [RunLifecycle.PAUSED] so the clock freezes identically, but it must not stop GPS — movement
     * is how it finds out to resume — and only an auto-pause may be auto-resumed.
     */
    val autoPaused: Boolean = false,

    val startedAtMillis: Long = 0,

    /**
     * The wall-clock time the last Tick was accounted for. The clock is driven from the difference
     * between ticks, never from counting them, so a tick that arrives late catches up.
     */
    val lastTickMillis: Long = 0,

    /**
     * The sub-second wall time each stretch had run up to but not yet banked as a whole second,
     * held for the stretch that is *not* currently accruing.
     *
     * The clock owes itself the leftover milliseconds between ticks (#147). Those milliseconds
     * belong to whichever stretch was live when they elapsed, so a pause or resume landing between
     * ticks must set them aside against the stretch it left and hand the next one a clean start —
     * otherwise the fraction of a running second still owed at a pause is banked as paused, or the
     * inverse. The live stretch's own leftover stays implicit in [lastTickMillis]; only the
     * suspended stretch's is parked here, and restored the moment it takes over again.
     */
    val runningRemainderMillis: Long = 0,
    val pausedRemainderMillis: Long = 0,

    /**
     * Work produced before the row id arrived, in the order it was produced.
     *
     * Internal bookkeeping rather than anything the screen reads. It exists so that the early
     * seconds of a Run have somewhere to go: the old code's answer was `if (sessionId != null)`,
     * which made dropping them the design.
     */
    val pendingRowEffects: List<PendingRowWork> = emptyList(),
) {
    /**
     * How long the current Phase lasts. The main Phase is open-ended (#107): an unplanned Run goes
     * until the runner stops it and a Workout's Intervals end themselves, so nothing times it out.
     */
    val phaseLimitSeconds: Long
        get() = when (phase) {
            RunPhase.WARM_UP -> config?.warmUpSeconds?.toLong() ?: 0L
            RunPhase.MAIN -> Long.MAX_VALUE
            RunPhase.COOL_DOWN -> config?.coolDownSeconds?.toLong() ?: 0L
        }

    /** Seconds left in the current Phase, and so 0 for the open-ended main one. */
    val phaseSecondsRemaining: Int
        get() = if (phase == RunPhase.MAIN) 0
        else (phaseLimitSeconds - phaseSecondsElapsed).coerceAtLeast(0).toInt()

    /**
     * Whether the coach is awake — the gate on *speaking* a zone cue (#108), as distinct from
     * whether a reading reaches the coach at all, which the Run asks separately.
     *
     * Awake only during a Workout's run Intervals, or, on a Run with no Intervals left to run, once
     * it is past its five-minute grace. The warm-up, every walk Interval and the cool-down are
     * silent, so nothing is coached while the runner is deliberately not at their target.
     *
     * A Workout whose Intervals are behind it leaves the Run open-ended in its main Phase, and from
     * that moment it is coached like any unplanned Run.
     */
    val coachingAwake: Boolean
        get() = when {
            phase != RunPhase.MAIN -> false
            // Not [inRunInterval]: that asks whether there is an Interval to measure, and this asks
            // whether the runner is meant to be running. Between the two, the seconds before the
            // first Interval opens are a run — which is what the service's initialised run step
            // makes them.
            config?.isRunWalkMode == true && !intervalsFinished ->
                intervals?.kind != IntervalKind.WALK
            else -> secondsRunning >= UNPLANNED_GRACE_SECONDS
        }

    /**
     * The tracker of the run Interval actually in progress, or null when there is not one.
     *
     * The coach's cue decisions write only through this, so a cue spoken during a walk Interval, an
     * unplanned Run, or the stretch after the Workout's last Interval records nothing rather than
     * inventing an Interval to hang itself on.
     */
    val runIntervalTracker: IntervalTracker?
        get() = intervalTracker
            ?.takeIf { phase == RunPhase.MAIN && intervals?.kind == IntervalKind.RUN }

    companion object {
        /** No Run. Every Run begins by replacing this wholesale, so nothing carries over. */
        val IDLE = RunState()
    }
}

/**
 * Work the Run has produced that cannot be an effect yet, because it is addressed to a database
 * row whose id has not come back.
 *
 * The Run keeps these in [RunState.pendingRowEffects] and turns each into an effect, in order, the
 * moment [RunEvent.RunRowCreated] lands. Everything else — speech, the notification, GPS — is
 * about the Run happening rather than the Run being recorded, and goes out immediately.
 *
 * The early seconds of a Run used to be discarded by `if (sessionId != null)`. This is where they
 * go instead: finalization is the one kind of held work the Run's spine produces, and the samples
 * and Interval stats of the seconds before the id lands join it here.
 */
sealed interface PendingRowWork {
    fun toEffect(runRowId: Long): RunEffect

    data class Finalize(val totals: RunTotals) : PendingRowWork {
        override fun toEffect(runRowId: Long): RunEffect = RunEffect.FinalizeRun(runRowId, totals)
    }

    /**
     * A run Interval that ended before the id arrived. Only reachable for a Workout with no
     * warm-up, whose first Interval can be over inside the first second of the Run — which is
     * exactly the case the old `if (sessionId != null)` threw away.
     */
    data class SaveIntervalStat(val stat: IntervalStat) : PendingRowWork {
        override fun toEffect(runRowId: Long): RunEffect =
            RunEffect.SaveIntervalStat(runRowId, stat)
    }

    /**
     * A second of heart rate banked before the id arrived. Every Run has a few: the row is created
     * asynchronously, and the seconds before it comes back were run like any others.
     */
    data class SaveHrSample(val sample: HrSampleReading) : PendingRowWork {
        override fun toEffect(runRowId: Long): RunEffect = RunEffect.SaveHrSample(runRowId, sample)
    }
}

/** What one event did: the Run's whole new state, and the effects the service is to perform. */
data class RunOutcome(
    val state: RunState,
    val effects: List<RunEffect> = emptyList(),
)
