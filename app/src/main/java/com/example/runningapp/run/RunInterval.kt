package com.example.runningapp.run

import kotlin.math.roundToInt

/**
 * Which kind of Interval the Run is in.
 *
 * The notification's label travels with it for the same reason [RunPhase]'s names do: it should
 * not be spelled at the call site. The coach names Intervals by their number rather than their
 * kind — "interval 2 of 6" — so unlike a Phase there is nothing spoken to carry here.
 */
enum class IntervalKind(
    /** As the notification labels it — "Int 3/6 • RUN • 01:20 left". */
    val notificationName: String,
) {
    RUN("RUN"),
    WALK("WALK"),
}

/**
 * The Interval the Run is in, and where that sits in the Workout.
 *
 * Present only while a Workout's Intervals are actually running: null before the first one begins
 * (the warm-up is still going) and null again once the last one is done. That is what the old
 * `isStructuredWorkout` / `hasStructuredWorkoutStarted` pair was saying between them, and reading
 * it off one nullable value means the screen and the notification cannot disagree about whether
 * there is an Interval to show.
 *
 * Everything the live screen shows about Workout progress is derived here rather than assembled
 * separately for the screen and for the notification, which is where the two used to drift.
 */
data class RunIntervals(
    val kind: IntervalKind,
    /** Which repeat of the Workout this is, counting from one. */
    val repeat: Int,
    val totalRepeats: Int,
    /** The Workout's prescribed lengths, kept so the Interval after this one can be described. */
    val runSeconds: Int,
    val walkSeconds: Int,
    val secondsRemaining: Int,
) {
    /** How long this Interval was prescribed to last. */
    val plannedSeconds: Int
        get() = when (kind) {
            IntervalKind.RUN -> runSeconds
            IntervalKind.WALK -> walkSeconds
        }.coerceAtLeast(0)

    /** How far into this Interval the runner is. */
    val secondsElapsed: Int
        get() = (plannedSeconds - secondsRemaining).coerceIn(0, plannedSeconds)

    /**
     * What comes after this Interval, or null if this is the last of the Workout.
     *
     * A Workout with no walk between its runs goes run → run, so the answer is not simply "the
     * other kind".
     */
    val nextKind: IntervalKind?
        get() = when {
            kind == IntervalKind.RUN && walkSeconds > 0 -> IntervalKind.WALK
            repeat < totalRepeats -> IntervalKind.RUN
            else -> null
        }

    /** How long that next Interval lasts, and 0 when there is not one. */
    val nextSeconds: Int
        get() = when (nextKind) {
            IntervalKind.RUN -> runSeconds
            IntervalKind.WALK -> walkSeconds
            null -> 0
        }

    /**
     * How far through the Workout's Intervals the runner is, as a percentage.
     *
     * Counted in Intervals rather than in seconds, and including the fraction of the current one,
     * so the bar moves smoothly and a walk step is worth as much of the bar as a run step.
     */
    val progressPercent: Int
        get() {
            val totalIntervals = if (walkSeconds > 0) totalRepeats * 2 else totalRepeats
            if (totalIntervals <= 0) return 0
            val completedBefore = when (kind) {
                IntervalKind.RUN ->
                    if (walkSeconds > 0) (repeat - 1).coerceAtLeast(0) * 2
                    else (repeat - 1).coerceAtLeast(0)
                IntervalKind.WALK -> ((repeat - 1).coerceAtLeast(0) * 2) + 1
            }.coerceAtLeast(0)
            val fraction =
                if (plannedSeconds > 0) secondsElapsed.toDouble() / plannedSeconds.toDouble()
                else 0.0
            val percent = ((completedBefore + fraction) / totalIntervals) * 100.0
            return percent.roundToInt().coerceIn(0, 100)
        }
}

/**
 * What one completed run Interval is saved as.
 *
 * The same numbers the app records today, minus the two the Run has no business filling in: the
 * row's own id and the Run's. Those belong to the write, so they arrive with
 * [RunEffect.SaveIntervalStat] instead, and the service maps this onto its database entity.
 *
 * Walk Intervals produce nothing. What is being measured is where the Run's heart rate went during
 * each prescribed run, and a walk step has no such number.
 *
 * The names date from when heart rate prescribed a walk, and outlive it (#167): nothing here sends
 * anyone walking now, so `actualRunningDurationBeforeHrTrigger` is how long the Interval ran before
 * the coach first spoke, and `totalTimeSpentWalkingDuringRunInterval` is time spent above target
 * rather than time spent walking — which the app has never been able to tell. See ADR 0003.
 */
data class IntervalStat(
    val intervalIndex: Int,
    val plannedDurationSeconds: Int,
    val actualRunningDurationBeforeHrTriggerSeconds: Int,
    val timeIntoIntervalWhenHrExceededCapSeconds: Int?,
    val hrTriggerEvents: Int,
    val totalTimeSpentWalkingDuringRunIntervalSeconds: Int,
    val avgHrAtTriggerInInterval: Double?,
    val avgRecoverySecondsAfterTriggerInInterval: Double?,
)

/**
 * The run Interval currently being measured.
 *
 * Bookkeeping rather than anything the screen reads — it becomes an [IntervalStat] when the
 * Interval ends, however it ends: prescribed length reached, main Phase skipped, or the Run
 * stopped mid-Interval. All three go through [toStat].
 *
 * The heart-rate fields are written by the coach's cue decisions and by nothing else, so an
 * Interval the coach never spoke into saves as a clean one — which is what it was.
 *
 * The old tracker took each trigger's second as `max(elapsed, sessionSecond - startSecond)`,
 * because the Bluetooth callback that recorded it and the timer that advanced `elapsed` were
 * different threads and either could be behind. There is one ordering here, so the Interval's own
 * elapsed count is simply the answer.
 */
data class IntervalTracker(
    val intervalIndex: Int,
    val plannedSeconds: Int,
    val secondsElapsed: Int = 0,
    /** The second the runner's heart rate first went over the cap, or null if it never did. */
    val firstHrTriggerSecond: Int? = null,
    val hrTriggerEvents: Int = 0,
    val walkingRecoverySeconds: Int = 0,
    val inRecoveryWindow: Boolean = false,
    val triggerHrSum: Double = 0.0,
    val triggerHrCount: Int = 0,
    val recoveryDurationSumSeconds: Int = 0,
    val recoveryEventCount: Int = 0,
    val activeRecoveryStartSecond: Int? = null,
) {
    /** One more second of this Interval, counted against the recovery window if one is open. */
    fun tick(): IntervalTracker = copy(
        secondsElapsed = secondsElapsed + 1,
        walkingRecoverySeconds =
            if (inRecoveryWindow) walkingRecoverySeconds + 1 else walkingRecoverySeconds,
    )

    /**
     * The coach told the runner their heart rate was high, [secondIntoInterval] seconds in.
     *
     * The first such second is the one that answers "how far into the Interval did the heart rate
     * first cross the line", so later Triggers in the same Interval do not overwrite it. Each one
     * opens a recovery window if one is not already open, which runs until they are told they are
     * back on target, or until the Interval ends.
     */
    fun hrTriggered(secondIntoInterval: Int, atBpm: Int): IntervalTracker = copy(
        firstHrTriggerSecond = firstHrTriggerSecond ?: secondIntoInterval,
        hrTriggerEvents = hrTriggerEvents + 1,
        triggerHrSum = triggerHrSum + atBpm,
        triggerHrCount = triggerHrCount + 1,
        inRecoveryWindow = true,
        activeRecoveryStartSecond = activeRecoveryStartSecond ?: secondIntoInterval,
    )

    /**
     * The runner is back on target. Closes the recovery a trigger opened, and only that: a return
     * cue with no trigger behind it — the runner drifted *below* target and came back — has no
     * recovery to close and leaves the numbers alone.
     */
    fun recovered(secondIntoInterval: Int): IntervalTracker =
        closeRecoveryWindow(secondIntoInterval).copy(inRecoveryWindow = false)

    /** The Interval is over. Any recovery still open is closed at the second it ended on. */
    fun toStat(): IntervalStat {
        val closed = closeRecoveryWindow(secondsElapsed)
        return IntervalStat(
            intervalIndex = closed.intervalIndex,
            plannedDurationSeconds = closed.plannedSeconds,
            // How long they held the run before their heart rate stopped them — the whole Interval
            // if it never did.
            actualRunningDurationBeforeHrTriggerSeconds =
                closed.firstHrTriggerSecond ?: closed.secondsElapsed,
            timeIntoIntervalWhenHrExceededCapSeconds = closed.firstHrTriggerSecond,
            hrTriggerEvents = closed.hrTriggerEvents,
            totalTimeSpentWalkingDuringRunIntervalSeconds = closed.walkingRecoverySeconds,
            avgHrAtTriggerInInterval =
                if (closed.triggerHrCount > 0) closed.triggerHrSum / closed.triggerHrCount else null,
            avgRecoverySecondsAfterTriggerInInterval =
                if (closed.recoveryEventCount > 0) {
                    closed.recoveryDurationSumSeconds.toDouble() / closed.recoveryEventCount
                } else {
                    null
                },
        )
    }

    private fun closeRecoveryWindow(endSecond: Int): IntervalTracker {
        val start = activeRecoveryStartSecond ?: return this
        return copy(
            recoveryDurationSumSeconds =
                recoveryDurationSumSeconds + (endSecond - start).coerceAtLeast(0),
            recoveryEventCount = recoveryEventCount + 1,
            activeRecoveryStartSecond = null,
        )
    }
}
