package com.example.runningapp.run

import com.example.runningapp.CueAction
import com.example.runningapp.CueCondition
import com.example.runningapp.CuePriority
import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.ZoneBand
import com.example.runningapp.bandWithHysteresis
import com.example.runningapp.coachingCue
import com.example.runningapp.highCueCondition
import com.example.runningapp.hrZoneOf

/**
 * What the coach says at the halfway point of an outdoor Run following a Workout (#208).
 *
 * Two short sentences, because it lands on a runner already moving and often already being spoken
 * to: what has happened, and what to do about it.
 */
internal const val TURNAROUND_CUE = "Halfway. Turn around."

/**
 * The Run: a rulebook, not an actor.
 *
 * One entry point takes a [RunEvent] and returns the Run's whole new [RunState] together with an
 * ordered list of [RunEffect]s for the service to perform. It never speaks, writes, notifies or
 * touches Android, has no clock of its own, holds no mutable field, and needs no lock.
 *
 * That is what turns the bugs this file has actually had into ordinary tests. They have never been
 * arithmetic bugs; they have been interleavings — STOP arriving while START was still creating the
 * database row, a pulse landing after the notification was removed, a dropout spanning a whole
 * walk step. Each of those is now a list of events and an expected result.
 *
 * See docs/adr/0002-the-run-is-a-rulebook-not-a-service.md.
 */
object Run {

    fun onEvent(state: RunState, event: RunEvent): RunOutcome = when (event) {
        is RunEvent.Started -> started(state, event)
        is RunEvent.RunRowCreated -> rowCreated(state, event)
        is RunEvent.Tick -> tick(state, event)
        is RunEvent.HeartRateSampled -> heartRateSampled(state, event)
        is RunEvent.HeartRateLost -> RunOutcome(
            state.copy(heartRate = state.heartRate.lost(event.connectionStatus)),
        )
        is RunEvent.ControlsChanged -> controlsChanged(state, event)
        is RunEvent.PauseToggled -> pauseToggled(state, event.nowMillis)
        // A named direction, so a button the shade has not caught up with asks for nothing.
        is RunEvent.PauseRequested ->
            if (state.lifecycle == RunLifecycle.RUNNING) pauseToggled(state, event.nowMillis) else RunOutcome(state)
        is RunEvent.ResumeRequested ->
            if (state.lifecycle == RunLifecycle.PAUSED) pauseToggled(state, event.nowMillis) else RunOutcome(state)
        is RunEvent.AutoPauseRequested -> autoPause(state, event.nowMillis)
        is RunEvent.AutoResumeRequested -> autoResume(state, event.nowMillis)
        is RunEvent.PhaseSkipped -> phaseSkipped(state, event)
        is RunEvent.Stopped -> stopped(state, event)
    }

    /**
     * START.
     *
     * The new state is built from [RunState.IDLE] rather than from the old one, so nothing — a
     * stale clock, a previous Run's counters, a Phase left in cool-down — can carry across. That
     * cluster of "reset for a fresh session" assignments was where a forgotten field silently
     * corrupted the next Run's summary.
     *
     * Ignored outright while a Run is live or is still waiting on its row id to finalize: exactly
     * one [RunEffect.CreateRunRow] per Run, and no second Run running invisibly behind the first.
     */
    private fun started(state: RunState, event: RunEvent.Started): RunOutcome {
        if (state.lifecycle != RunLifecycle.IDLE && state.lifecycle != RunLifecycle.STOPPED) {
            return RunOutcome(state)
        }
        val started = RunState.IDLE.copy(
            lifecycle = RunLifecycle.RUNNING,
            config = event.config,
            controls = event.controls,
            startedAtMillis = event.nowMillis,
            lastTickMillis = event.nowMillis,
        )
        return RunOutcome(
            started,
            listOf(
                RunEffect.CreateRunRow(
                    startedAtMillis = event.nowMillis,
                    targetZoneNumber = event.config.targetZone.number,
                    runModeSettingValue = event.config.runMode.settingValue,
                    includeInAiTraining = event.config.includeInAiTraining,
                    hrProfile = event.config.hrProfile,
                    ranUnderStageId = event.config.ranUnderStageId,
                    ranUnderWorkoutId = event.config.workout?.id,
                ),
            ),
        )
    }

    /**
     * The row id came back. The Run becomes recordable, and everything held for this moment goes
     * out in the order it was produced.
     *
     * GPS starts here rather than at START for the same reason: a fix can arrive immediately, and
     * one that lands before the id has no row to be written against, which used to clip the first
     * stretch of the route off the map.
     *
     * A Run that was stopped while waiting finalizes here and starts nothing.
     */
    private fun rowCreated(state: RunState, event: RunEvent.RunRowCreated): RunOutcome {
        if (state.runRowId != null) return RunOutcome(state)
        if (state.lifecycle == RunLifecycle.IDLE || state.lifecycle == RunLifecycle.STOPPED) {
            return RunOutcome(state)
        }
        val effects = mutableListOf<RunEffect>()
        val stopping = state.lifecycle == RunLifecycle.STOPPING
        // Location runs only while the Run is running *and* has its row id. A Run paused inside the
        // row-creation window must not have GPS started for it when the id lands — the screen would
        // say paused while the route kept drawing. A resume is what starts it (#146).
        if (state.lifecycle == RunLifecycle.RUNNING && state.config?.runMode == RunMode.OUTDOOR) {
            effects += RunEffect.StartGps
        }
        state.pendingRowEffects.forEach { effects += it.toEffect(event.runRowId) }
        return RunOutcome(
            state.copy(
                runRowId = event.runRowId,
                lifecycle = if (stopping) RunLifecycle.STOPPED else state.lifecycle,
                pendingRowEffects = emptyList(),
            ),
            effects,
        )
    }

    /**
     * A second passed — or several, if the pulse was late.
     *
     * The clock is the difference between this tick's wall-clock time and the last one's, so a
     * janky screen or a dozing phone costs the Run no seconds: a tick arriving five seconds late
     * advances five seconds.
     *
     * The sub-second remainder is carried rather than dropped: the clock advances by the whole
     * seconds elapsed and keeps the leftover milliseconds against the next tick, so the Run's clock
     * tracks the wall instead of shedding a slice on every pulse. The pulses land a shade over a
     * second apart, so dropping the remainder leaked steadily in one direction — about half a
     * percent slow, a dozen-odd seconds short over a long run (#147).
     */
    private fun tick(state: RunState, event: RunEvent.Tick): RunOutcome = accrue(state, event.nowMillis)

    /**
     * Account the whole seconds elapsed since the last tick under the lifecycle in force.
     *
     * The same reckoning a [tick] is, factored out so a pause or resume can settle the clock up to
     * the instant it changes hands before it changes hands — the seconds already run (or already
     * paused) at that instant are banked to the stretch they belong to, not to the one taking over.
     */
    private fun accrue(state: RunState, nowMillis: Long): RunOutcome {
        if (!state.lifecycle.isLive) return RunOutcome(state)
        val elapsedSeconds = (nowMillis - state.lastTickMillis) / 1000
        if (elapsedSeconds < 1) return RunOutcome(state)
        // Advance by whole seconds only; the remainder stays owed against the next tick, which
        // measures from here rather than from this pulse's exact arrival.
        val previousTickMillis = state.lastTickMillis
        val accounted = state.copy(lastTickMillis = previousTickMillis + elapsedSeconds * 1000)
        return when (accounted.lifecycle) {
            RunLifecycle.PAUSED -> RunOutcome(
                accounted.copy(secondsPaused = accounted.secondsPaused + elapsedSeconds),
            )
            else -> advanceRunningSeconds(accounted, elapsedSeconds, previousTickMillis)
        }
    }

    /**
     * The per-second accounting, one second at a time even when catching up, so a Phase boundary
     * that falls inside the gap is honoured at the second it belongs to rather than jumped over.
     */
    private fun advanceRunningSeconds(state: RunState, seconds: Long, previousTickMillis: Long): RunOutcome {
        var current = state
        val effects = mutableListOf<RunEffect>()
        var ended = false

        for (i in 1..seconds) {
            // The Run banks every second it runs; the Phase banks one only when its own clock
            // completes a second. The two differ only after a skip taken part-way through a second,
            // which leaves the new Phase owing that fraction — see [RunState.phaseCarryMillis].
            val phaseMillis = current.phaseCarryMillis + 1000
            val phaseSecond = phaseMillis >= 1000
            current = current.copy(
                secondsRunning = current.secondsRunning + 1,
                phaseSecondsElapsed = current.phaseSecondsElapsed + if (phaseSecond) 1 else 0,
                phaseCarryMillis = if (phaseSecond) phaseMillis - 1000 else phaseMillis,
            )
            val limit = current.phaseLimitSeconds
            val remaining = limit - current.phaseSecondsElapsed

            if (phaseSecond && current.phase != RunPhase.MAIN && remaining == 10L) {
                effects += RunEffect.Speak("10 seconds of ${current.phase.spokenName} remaining", CuePriority.INSTRUCTION)
            }

            // A Run with no Workout has a nought-second warm-up, so it hands over on its first
            // second — announcing the main workout to a runner who was never told to warm up. That
            // is what the Run does today, and this move is not the place to change what it says.
            if (phaseSecond && current.phase == RunPhase.WARM_UP && current.phaseSecondsElapsed >= limit) {
                current = current.copy(phase = RunPhase.MAIN, phaseSecondsElapsed = 0)
                effects += RunEffect.Speak("Starting main workout", CuePriority.INSTRUCTION)
            } else if (phaseSecond && current.phase == RunPhase.COOL_DOWN && current.phaseSecondsElapsed >= limit) {
                // Bank the terminating second before ending on it. The Run counts it toward its
                // duration, so its zone (or no-data) total must count it too, and it writes its HR
                // sample like any other second — otherwise the bands beneath the duration are one
                // second short of it on every planned Run (#152).
                val banked = bankSecond(current, previousTickMillis + i * 1000)
                current = banked.state
                effects += banked.effects
                // The Run ends itself. Nothing calls back in to stop it — that round trip was one
                // more ordering for the service to get right. The end time is the second the Run
                // actually ended on, counted along this loop, not the clock reading of a tick that
                // may have arrived long after the phone woke — those two used to disagree (#146).
                val end = finish(current, previousTickMillis + i * 1000)
                current = end.state
                effects += end.effects
                ended = true
                break
            }

            // Halfway, door to door. Asked every second rather than worked out once at START,
            // because the answer moves: a skip shortens the Run under it. Asked before the
            // Intervals advance, so it is settled against the Phase this second belongs to.
            val halfway = turnaroundReached(current)
            if (halfway) {
                // Marked whether or not it is said. Halfway happening is a fact about the Run, not
                // about the setting: a runner who switches the cue on at minute 40 of a 47-minute
                // Run must not be told to turn around within sight of home.
                current = current.copy(turnaroundCued = true)
            }

            // The Workout's Intervals, on the same second and after the handover, so the second
            // the warm-up ends is also the second the first Interval begins — the runner hears
            // "Starting main workout" and then "Start running, interval 1 of 6" without a gap.
            // The Intervals run on the Phase's clock, not the Run's: they are the main Phase's
            // seconds, so a skip into it must not spend the fraction owed to the warm-up on the
            // first Interval.
            if (phaseSecond) {
                val stepped = advanceIntervalSecond(current)
                current = stepped.state
                effects += stepped.effects
            }

            // Decided above, said here: last of everything this second produced. Halfway can land
            // on the very second an Interval begins, and the queue is first in, first out within a
            // level — so emitting it after the Interval's own instruction is what keeps the two in
            // the order the runner needs them, on a second where the instruction is the urgent one.
            // (It outranks the turnaround anyway; this makes the order not depend on that.)
            //
            // The Phase is asked again for the same reason: the Intervals can hand the Run into its
            // cool-down on this very second — a Workout whose cool-down is as long as everything
            // before it puts halfway exactly there — and the runner is heading home from that
            // second, whichever half of it the arithmetic belongs to.
            if (halfway && current.controls.turnaroundCueEnabled && current.phase != RunPhase.COOL_DOWN) {
                current = current.copy(turnaroundHeld = true)
                effects += RunEffect.Speak(TURNAROUND_CUE, CuePriority.INFORMATION, CueTag.TURNAROUND)
            }

            // Ten minutes in, on a Run that has a reading to pin. What "drifting up" is measured
            // against for the rest of the Run — see [highCueCondition].
            if (current.secondsRunning == DRIFT_BASELINE_SECOND && current.heartRate.bpm > 0) {
                current = current.copy(
                    coaching = current.coaching.copy(baselineHr = current.heartRate.bpm),
                )
            }

            val banked = bankSecond(current, previousTickMillis + i * 1000)
            current = banked.state
            effects += banked.effects
        }

        // One refresh per pulse, however many seconds it accounted for, and none for a Run that
        // just ended. How often that reaches the platform is the performer's business: it already
        // throttles a backgrounded notification, which turns on whether an activity is bound —
        // an Android fact the Run has no way of knowing and no reason to.
        if (!ended) effects += RunEffect.Notify(notificationText(current))
        return RunOutcome(current, effects)
    }

    /**
     * Whether this second is the halfway point of the Run — the moment to turn for home (#208).
     *
     * Halfway is measured in time, not distance: a Workout prescribes durations, so the Run knows
     * its length from START and has no distance target to halve. The clock is moving time — the
     * same clock the Intervals run on — so a pause pushes the turnaround later by exactly the time
     * spent standing still.
     *
     * Never in the cool-down. A Run reaching its cool-down the ordinary way is past halfway
     * already; one skipped there has thrown its remaining Intervals away and left halfway behind
     * it. Either way the runner is heading home, and "turn around" would be actively wrong — so the
     * one case this also covers, a shortened Run whose halfway lands inside the cool-down, is
     * rightly silent too.
     *
     * The setting is not asked here: whether to *say* it is separate from whether it *happened*.
     */
    private fun turnaroundReached(state: RunState): Boolean {
        if (state.turnaroundCued) return false
        val config = state.config ?: return false
        // Nowhere to turn around to on a treadmill; nothing to halve without a Workout.
        if (config.runMode != RunMode.OUTDOOR || config.workout == null) return false
        if (state.phase == RunPhase.COOL_DOWN) return false
        val total = state.projectedMovingSeconds ?: return false
        return state.secondsRunning * 2 >= total
    }

    /**
     * The cool-down takes back a turnaround that has not been spoken yet (#208).
     *
     * The runner is heading home from here, whether the Workout ended or they skipped past what was
     * left of it. "Turn around" was true when it was issued and is not true now, and a cue that may
     * wait its turn in the queue is a cue that can be overtaken like this — so it is withdrawn
     * rather than spoken a few seconds into the cool-down.
     */
    private fun coolDownDropsWaitingCue(state: RunState): List<RunEffect> =
        if (state.turnaroundHeld) listOf(RunEffect.WithdrawCue(CueTag.TURNAROUND)) else emptyList()

    /**
     * Bank one second of the Run against the reading that stands at this moment.
     *
     * The reading, not a fresh one: a Run banks a second whether or not a packet arrived for it, and
     * a late pulse catching up on five seconds banks all five against the one reading it has. That
     * is what makes a dropout honest — the seconds are counted as no-data rather than fabricated
     * from a stale last reading, and rather than dropped, which used to leave a long outage
     * silently missing from the summary's zone breakdown (#110).
     *
     * Every zone edge is measured against the Max HR pinned at START, never against Settings as it
     * stands now.
     */
    private fun bankSecond(state: RunState, atMillis: Long): RunOutcome {
        val config = state.config ?: return RunOutcome(state)
        val bpm = state.heartRate.bpm
        // One question, asked once: was there a reading? Zone 1 swallows everything beneath it, so
        // hrZoneOf answers null for exactly the seconds that had none. #115's second branch — a
        // no-data second banked from inside a positive-reading guard, which nothing could reach —
        // has nowhere here to be written.
        val zone = hrZoneOf(bpm, config.hrProfile)
            ?: return RunOutcome(state.copy(tally = state.tally.bankNoData()))
        return emitOrHold(
            state.copy(tally = state.tally.bank(zone, bpm, config.hrProfile, config.targetZone)),
            PendingRowWork.SaveHrSample(
                HrSampleReading(
                    elapsedSeconds = state.secondsRunning,
                    atMillis = atMillis,
                    rawBpm = bpm,
                    smoothedBpm = state.heartRate.smoothedBpm,
                    connectionStatus = state.heartRate.connectionStatus,
                ),
            ),
        )
    }

    /**
     * A reading arrived from the Strap, and with it the coach's one chance to speak.
     *
     * The reading is always kept — it is what the next second banks as — and it always joins the
     * rolling average, which belongs to the Strap rather than to the coach (ADR 0011, #161).
     * Whether the coach so much as looks at it is three separate questions, in this order: is the
     * Run actually running, is it somewhere cues are allowed at all (never the cool-down), and has
     * the runner left coaching on. A "no" to any of them leaves the coach silent, and nothing else.
     */
    private fun heartRateSampled(state: RunState, event: RunEvent.HeartRateSampled): RunOutcome {
        val config = state.config
        val reading = state.heartRate.sampled(event.bpm, event.connectionStatus, event.nowMillis)
        val listening = state.lifecycle == RunLifecycle.RUNNING &&
            state.phase != RunPhase.COOL_DOWN &&
            state.controls.coachingEnabled
        if (config == null || !listening) return RunOutcome(state.copy(heartRate = reading))

        // One band, one clock. Hysteresis judges re-entry at the target zone's midpoint, so a heart
        // rate parked on an edge cannot farm return cues; the ladder decides when to speak; the
        // band decides what is said. Hysteresis carries only across consecutive awake samples —
        // asleep, the band is UNKNOWN, so a run Interval can never inherit a stale ABOVE and speak
        // over a heart rate that has since settled into target.
        val awake = state.coachingAwake
        val band =
            if (awake) {
                bandWithHysteresis(state.coaching.band, reading.smoothedBpm, config.hrProfile, config.targetZone)
            } else {
                ZoneBand.UNKNOWN
            }
        val step = state.coaching.ladder.onSample(event.nowMillis, band, awake)
        val current = state.copy(
            heartRate = reading,
            coaching = state.coaching.copy(band = band, ladder = step.ladder),
        )

        return when (step.action) {
            CueAction.SPEAK -> when (band) {
                ZoneBand.ABOVE -> highCue(current, reading.smoothedBpm)
                ZoneBand.BELOW -> RunOutcome(current, spoken(CueCondition.BELOW))
                else -> RunOutcome(current)
            }
            CueAction.RETURN -> returnCue(current)
            CueAction.SILENT -> RunOutcome(current)
        }
    }

    /**
     * The words for an above-target cue, the ladder having already decided it is time to speak.
     *
     * Advice, and only advice (#167): both sentences ask for a change of effort, neither prescribes
     * a walk, and nothing in the Run's state moves because the runner ignored one. The Trigger is
     * still recorded — that is the readout the runner wants afterwards — but it is no longer a
     * verdict on them. No spoken cue names a zone (#109): the voice asks for a change, and the
     * screen reports the state.
     */
    private fun highCue(state: RunState, smoothedBpm: Int): RunOutcome {
        val condition = highCueCondition(
            secondsRunning = state.secondsRunning,
            baselineHr = state.coaching.baselineHr,
            avgBpm = smoothedBpm,
        )
        return RunOutcome(recordHighHrTrigger(state, smoothedBpm), spoken(condition))
    }

    /**
     * The closing bracket of a spoken cue (#108): the runner was told they had drifted out, they
     * came back past the midpoint, and this tells them they are home so they stop guessing.
     *
     * It fires only because the ladder saw a cue actually spoken while they were out, and the
     * wording is direction-neutral because they can re-enter from either side.
     */
    private fun returnCue(state: RunState): RunOutcome =
        RunOutcome(recordRecovery(state), spoken(CueCondition.RETURNED))

    private fun spoken(condition: CueCondition): List<RunEffect> =
        listOfNotNull(coachingCue(condition).spoken?.let { RunEffect.Speak(it, CuePriority.COACHING) })

    /**
     * The runner's heart rate went above target and the coach said so. Where that happened, kept so
     * the Interval can be read back afterwards.
     *
     * Only a run Interval has a number for it — an unplanned Run, a walk Interval and the stretch
     * after the Workout's last Interval all record nothing, so the coach can still speak there
     * without inventing an Interval to hang the Trigger on.
     */
    private fun recordHighHrTrigger(state: RunState, smoothedBpm: Int): RunState {
        val tracker = state.runIntervalTracker ?: return state
        return state.copy(
            intervalTracker = tracker.hrTriggered(tracker.secondsElapsed, smoothedBpm),
            trigger = state.trigger.triggered(tracker.secondsElapsed),
        )
    }

    /** Back on target after a trigger: the recovery it opened closes here, at this second. */
    private fun recordRecovery(state: RunState): RunState {
        val tracker = state.runIntervalTracker ?: return state
        return state.copy(intervalTracker = tracker.recovered(tracker.secondsElapsed))
    }

    /**
     * One second of the Workout's Intervals.
     *
     * A Run with no Workout never gets here, and neither does one whose Intervals are behind it —
     * which matters more than it looks, because finishing the Workout does not move the Run out of
     * the main Phase. Without [RunState.intervalsFinished] the next second would start the whole
     * Workout over.
     */
    private fun advanceIntervalSecond(state: RunState): RunOutcome {
        val config = state.config ?: return RunOutcome(state)
        val workout = config.workout ?: return RunOutcome(state)
        if (state.phase != RunPhase.MAIN || state.intervalsFinished) return RunOutcome(state)
        if (workout.totalRepeats <= 0) return RunOutcome(state)

        var current = state
        val effects = mutableListOf<RunEffect>()

        if (current.intervals == null) {
            // The Intervals wait out the warm-up — unless the runner skipped it, in which case
            // they begin the moment the main Phase does.
            if (!current.warmUpSkipped && current.secondsRunning < config.warmUpSeconds) {
                return RunOutcome(state)
            }
            val begun = beginRunInterval(current, workout, repeat = 1)
            current = begun.state
            effects += begun.effects
        }

        // A Workout prescribing intervals of no length has nothing to count down, and stays where
        // it is rather than spinning through its repeats in a single second.
        val intervals = current.intervals ?: return RunOutcome(current, effects)
        if (intervals.secondsRemaining <= 0) return RunOutcome(current, effects)

        current = current.copy(
            intervals = intervals.copy(secondsRemaining = intervals.secondsRemaining - 1),
            // Only a run Interval is being measured; a walk one has no tracker to advance.
            intervalTracker = current.intervalTracker?.tick(),
        )

        if (current.intervals?.secondsRemaining == 0) {
            val completed = completeInterval(current, workout)
            current = completed.state
            effects += completed.effects
        }
        return RunOutcome(current, effects)
    }

    /** The runner is sent running: a fresh Interval, and a fresh set of numbers to measure it by. */
    private fun beginRunInterval(
        state: RunState,
        workout: WorkoutTemplate,
        repeat: Int,
    ): RunOutcome {
        val runSeconds = workout.runDurationSeconds
        val begun = state.copy(
            intervals = RunIntervals(
                kind = IntervalKind.RUN,
                repeat = repeat,
                totalRepeats = workout.totalRepeats,
                runSeconds = runSeconds,
                walkSeconds = workout.walkDurationSeconds.coerceAtLeast(0),
                secondsRemaining = runSeconds,
            ),
            // An Interval of no length measures nothing, so there is nothing to save for it.
            intervalTracker =
                if (runSeconds > 0) IntervalTracker(repeat, runSeconds) else null,
            // Every run Interval starts the coach from scratch — a fresh ladder, no band carried
            // in — and forgets why the last walk was taken, since the last Interval's high heart
            // rate does not explain this one's walk. The reset is timer-driven rather than
            // packet-driven on purpose: the walk's own reset rides on a sample arriving while the
            // coach is asleep, so a Strap dropout spanning a whole walk lands none, and the next
            // Interval would reuse the last one's ladder and fire an immediate catch-up cue
            // (Codex #124). The drift baseline is the Run's rather than the Interval's and stays.
            //
            // Tied to there being an Interval to measure, which is what the service ties it to.
            // A Workout prescribing runs of no length therefore leaves the coach exactly as it
            // was — reproduced rather than chosen, and unreachable by any Workout the app writes.
            coaching = if (runSeconds > 0) state.coaching.startAgain() else state.coaching,
            trigger = if (runSeconds > 0) Trigger() else state.trigger,
        )
        return RunOutcome(
            begun,
            listOf(
                RunEffect.Speak(
                    "Start running, interval $repeat of ${workout.totalRepeats}.",
                    CuePriority.INSTRUCTION,
                ),
            ),
        )
    }

    /**
     * An Interval reached its prescribed length.
     *
     * A run hands over to its walk if the Workout prescribes one; a walk — or a run in a Workout
     * with no walk — advances the repeat. The last one hands the Run back to the main Phase, which
     * is where an unplanned Run has been all along.
     */
    private fun completeInterval(state: RunState, workout: WorkoutTemplate): RunOutcome {
        val intervals = state.intervals ?: return RunOutcome(state)
        var current = state
        val effects = mutableListOf<RunEffect>()

        if (intervals.kind == IntervalKind.RUN) {
            val saved = saveRunInterval(current)
            current = saved.state
            effects += saved.effects
            if (intervals.walkSeconds > 0) {
                return RunOutcome(
                    current.copy(
                        intervals = intervals.copy(
                            kind = IntervalKind.WALK,
                            secondsRemaining = intervals.walkSeconds,
                        ),
                        // The Workout's walk, counted as the Run takes it. This is the only place a
                        // walk break is counted (#167): the coach used to add one every time it
                        // spoke about heart rate, which made the number a tally of line-crossings
                        // rather than of walks.
                        walkBreaks = current.walkBreaks + 1,
                        // The Trigger belonged to the run Interval that has just ended. Carried
                        // into the walk it becomes a reason for it — the live screen reads a
                        // standing Trigger as the cue in force and marks the walk "Safety cue
                        // active", which is heart rate claiming a prescribed walk by the back door.
                        trigger = Trigger(),
                    ),
                    effects + RunEffect.Speak(
                        "Transition to walking, ${intervals.walkSeconds} seconds.",
                        CuePriority.INSTRUCTION,
                    ),
                )
            }
        }

        val nextRepeat = intervals.repeat + 1
        return if (nextRepeat > intervals.totalRepeats) {
            RunOutcome(
                current.copy(
                    intervals = null,
                    intervalsFinished = true,
                    // No Interval left to explain.
                    trigger = Trigger(),
                    // The Workout's last Interval hands the Run into its cool-down, the way the
                    // warm-up hands it into the main Phase — so the sentence the coach speaks here
                    // becomes true. From this second the cool-down's ten-second warning and its own
                    // auto-stop start firing (#150). A Workout with a nought-second cool-down ends
                    // on the next tick, exactly as skipping the main Phase into one already does; an
                    // unplanned Run has no Interval to complete and never reaches here.
                    phase = RunPhase.COOL_DOWN,
                    phaseSecondsElapsed = 0,
                    turnaroundHeld = false,
                ),
                effects + coolDownDropsWaitingCue(current) +
                    RunEffect.Speak("Main workout complete, beginning cool down.", CuePriority.INSTRUCTION),
            )
        } else {
            val begun = beginRunInterval(current, workout, nextRepeat)
            RunOutcome(begun.state, effects + begun.effects)
        }
    }

    /**
     * Bank the run Interval that just ended, however it ended — prescribed length reached, main
     * Phase skipped, or the Run stopped part-way through one. The seconds were run either way.
     *
     * An Interval that ends before the row id has arrived is held rather than dropped. Only a
     * Workout with no warm-up can manage it, but that is precisely the Run whose first Interval
     * the old `if (sessionId != null)` used to throw away.
     */
    private fun saveRunInterval(state: RunState): RunOutcome {
        val tracker = state.intervalTracker ?: return RunOutcome(state)
        return emitOrHold(
            state.copy(intervalTracker = null),
            PendingRowWork.SaveIntervalStat(tracker.toStat()),
        )
    }

    /**
     * Work addressed to the Run's row: sent now if the row exists, and kept in order if it does
     * not.
     *
     * Every kind of held work goes through here, so nothing the Run produces can be dropped by a
     * new call site forgetting to ask whether the id had arrived — which is precisely how the
     * early seconds of a Run used to be lost.
     */
    private fun emitOrHold(state: RunState, work: PendingRowWork): RunOutcome {
        val runRowId = state.runRowId
        return if (runRowId != null) {
            RunOutcome(state, listOf(work.toEffect(runRowId)))
        } else {
            RunOutcome(state.copy(pendingRowEffects = state.pendingRowEffects + work))
        }
    }

    /**
     * The runner flipped a control mid-Run.
     *
     * Obeyed from the moment it arrives, which is the whole point of a control (#109): the very next
     * sample is judged under the new one, so coaching turned off goes quiet at once.
     *
     * "The moment it arrives" is why the clock is settled first, exactly as a pause, a skip or a
     * STOP settles it. Seconds the runner had already run when they flipped the switch were run
     * under the old controls; a pulse the phone was slow to deliver must not hand them to the new
     * one. Without that, enabling the turnaround while a tick was overdue let the catch-up loop
     * reach a halfway that had already passed and speak it (Codex, #212).
     *
     * A control turned off can also take back a cue not yet spoken — see [RunEffect.WithdrawCue].
     */
    private fun controlsChanged(state: RunState, event: RunEvent.ControlsChanged): RunOutcome {
        val settled = accrue(state, event.nowMillis)
        val current = settled.state
        val dropped =
            if (current.turnaroundHeld && !event.controls.turnaroundCueEnabled) {
                listOf(RunEffect.WithdrawCue(CueTag.TURNAROUND))
            } else {
                emptyList()
            }
        return RunOutcome(
            current.copy(controls = event.controls, turnaroundHeld = current.turnaroundHeld && dropped.isEmpty()),
            settled.effects + dropped,
        )
    }

    /**
     * The pause/resume button. Only a live Run can be paused, and only a paused one resumed.
     *
     * Settling the clock up to [nowMillis] is the whole reason the time rides in here. The seconds
     * already run at the instant of a pause are banked as running before the Run pauses — a pulse
     * the phone was slow to deliver does not lose them — and the leftover sub-second still owed is
     * set aside against the running stretch rather than counted as paused. Resuming is the mirror.
     * See [changeLifecycle] and [RunState.runningRemainderMillis].
     */
    private fun pauseToggled(state: RunState, nowMillis: Long): RunOutcome = when (state.lifecycle) {
        RunLifecycle.RUNNING -> changeLifecycle(state, nowMillis, RunLifecycle.PAUSED, autoPaused = false) { paused ->
            // A tapped pause stops GPS; an auto-pause deliberately does not.
            listOf(RunEffect.StopGps, RunEffect.Notify(notificationText(paused)))
        }
        RunLifecycle.PAUSED -> changeLifecycle(state, nowMillis, RunLifecycle.RUNNING, autoPaused = false) { resumed ->
            buildList {
                // The mode the Run started with, not whatever the setting says now — and only once
                // the row id has arrived. A Run resumed inside the row-creation window starts no
                // location; the id landing, with the Run now running, is what starts it, exactly
                // once (#146).
                if (resumed.config?.runMode == RunMode.OUTDOOR && resumed.runRowId != null) {
                    add(RunEffect.StartGps)
                }
                add(RunEffect.Notify(notificationText(resumed)))
            }
        }
        // A stale notification action — the shade lags the Run — must not flip a stopped Run back
        // to paused, nor republish a running one over a Run that is already being finalized.
        else -> RunOutcome(state)
    }

    /**
     * Freeze one stretch of the Run and start the next, drawing the line at [nowMillis].
     *
     * First the clock is settled: every whole second run (or paused) since the last tick is banked
     * to the stretch that is ending — with all a running second's own work, so nothing about those
     * seconds differs from a tick landing at that instant. A settle that ends the Run — a late pulse
     * that carried it over its cool-down line as it was being paused — is returned as it stands;
     * there is no stretch left to hand to.
     *
     * Then the leftover sub-second still owed is parked against the ending stretch, and the incoming
     * stretch's own parked leftover is restored into [RunState.lastTickMillis], so each resumes owing
     * exactly the fraction of a second it left off owing.
     */
    private fun changeLifecycle(
        state: RunState,
        nowMillis: Long,
        to: RunLifecycle,
        autoPaused: Boolean,
        effects: (RunState) -> List<RunEffect>,
    ): RunOutcome {
        val settled = accrue(state, nowMillis)
        val current = settled.state
        if (current.lifecycle == to || !current.lifecycle.isLive) return settled
        val leftoverMillis = nowMillis - current.lastTickMillis
        val leaving = current.lifecycle
        val changed = current.copy(
            lifecycle = to,
            autoPaused = autoPaused,
            // Park the leaving stretch's owed fraction; restore the incoming one's into
            // lastTickMillis, where a live stretch keeps its own, and zero it there.
            lastTickMillis = nowMillis - when (to) {
                RunLifecycle.RUNNING -> current.runningRemainderMillis
                else -> current.pausedRemainderMillis
            },
            runningRemainderMillis = if (leaving == RunLifecycle.RUNNING) leftoverMillis else 0,
            pausedRemainderMillis = if (leaving == RunLifecycle.PAUSED) leftoverMillis else 0,
            // The near side of a Pause is remembered here and nowhere else — see [closeAnyPause].
            pausedAtMillis = if (to == RunLifecycle.PAUSED) nowMillis else null,
        )
        val recorded =
            if (leaving == RunLifecycle.PAUSED) closeAnyPause(current, changed, nowMillis)
            else RunOutcome(changed)
        return RunOutcome(recorded.state, settled.effects + recorded.effects + effects(recorded.state))
    }

    /**
     * Write down the Pause that has just ended (#328).
     *
     * A Pause is saved as it ends, because that is the first moment both its sides are known — and
     * every way out of a Pause comes through here or through [finish]. [paused] is the state that
     * held the Pause's near side; [ended] is what the Run has become.
     *
     * Silent where no Pause was open, which is most Runs at the moment they STOP: [finish] asks
     * every Run on its way out rather than asking first whether it was paused, so there is one place
     * a Pause is closed and no second rule to keep in step with it.
     */
    private fun closeAnyPause(paused: RunState, ended: RunState, nowMillis: Long): RunOutcome {
        val startedAtMillis = paused.pausedAtMillis ?: return RunOutcome(ended)
        return emitOrHold(
            ended.copy(pausedAtMillis = null),
            PendingRowWork.SavePause(PauseTaken(startedAtMillis, nowMillis)),
        )
    }

    /**
     * A sustained standstill (#39). Freezes the clock exactly like a tapped pause, but keeps GPS.
     *
     * Unconditional on [RunControls.autoPauseEnabled]: the standstill is detected by the module
     * that watches the fixes, and that is where the toggle is honoured. A request that reaches
     * here has already been decided.
     */
    private fun autoPause(state: RunState, nowMillis: Long): RunOutcome {
        if (state.lifecycle != RunLifecycle.RUNNING) return RunOutcome(state)
        // Settles the clock at the moment of the change like a tapped pause — see [changeLifecycle].
        return changeLifecycle(state, nowMillis, RunLifecycle.PAUSED, autoPaused = true) { paused ->
            listOf(RunEffect.Notify(notificationText(paused)), RunEffect.Speak("Auto-paused.", CuePriority.INSTRUCTION))
        }
    }

    /** Movement again. Only an auto-pause may be undone this way; a tapped pause is the runner's. */
    private fun autoResume(state: RunState, nowMillis: Long): RunOutcome {
        if (state.lifecycle != RunLifecycle.PAUSED || !state.autoPaused) return RunOutcome(state)
        // Settles the clock at the moment of the change like a tapped resume — see [changeLifecycle].
        return changeLifecycle(state, nowMillis, RunLifecycle.RUNNING, autoPaused = false) { resumed ->
            listOf(RunEffect.Notify(notificationText(resumed)), RunEffect.Speak("Resuming.", CuePriority.INSTRUCTION))
        }
    }

    /**
     * The skip button. Each Phase hands over to the next; skipping the cool-down ends the Run.
     *
     * The clock is settled up to the tap first, exactly as [changeLifecycle] does: the seconds
     * already run since the last tick belong to the Phase being left, not the one taking over.
     * Without that, a late pulse's carried remainder was spent against the new Phase, advancing it
     * by nearly a second it never ran. A settle that ends the Run — a skip landing as the cool-down
     * line was crossed — is returned as it stands.
     *
     * The skip is the one the runner saw. If settling carries the Run over a Phase line itself, the
     * Phase the button named is already over and the tap has nothing left to skip: "Skip Warm Up"
     * pressed on the warm-up's last second must not go on to skip the whole workout with it.
     */
    private fun phaseSkipped(state: RunState, event: RunEvent.PhaseSkipped): RunOutcome {
        if (!state.lifecycle.isLive) return RunOutcome(state)
        val tapped = state.phase
        val settled = accrue(state, event.nowMillis)
        val current = settled.state
        if (!current.lifecycle.isLive || current.phase != tapped) return settled
        val outcome = skipPhase(current, event.nowMillis)
        return RunOutcome(outcome.state, settled.effects + outcome.effects)
    }

    /**
     * The skip itself, on a Run whose clock is already settled to the instant of the tap.
     *
     * The fraction of a second still owed at that instant was run under the Phase being left, so
     * the incoming Phase starts owing it: its first second lands a full second after the tap rather
     * than on the Run's next whole-second boundary, which would have been a shade early. Skipping
     * while paused owes the same fraction: the running one parked at the pause, which resuming
     * restores — not the paused fraction elapsing now, which no Phase clock counts.
     */
    private fun skipPhase(state: RunState, nowMillis: Long): RunOutcome {
        val owed = when (state.lifecycle) {
            RunLifecycle.RUNNING -> nowMillis - state.lastTickMillis
            else -> state.runningRemainderMillis
        }
        return when (state.phase) {
            RunPhase.WARM_UP -> {
                val skipped = state.copy(
                    phase = RunPhase.MAIN,
                    phaseSecondsElapsed = 0,
                    phaseCarryMillis = -owed,
                    warmUpSkipped = true,
                )
                RunOutcome(
                    skipped,
                    listOf(
                        RunEffect.Speak("Warm up skipped. Starting workout.", CuePriority.INSTRUCTION),
                        RunEffect.Notify(notificationText(skipped)),
                    ),
                )
            }
            RunPhase.MAIN -> {
                // Skipping the main Phase abandons whatever is left of the Workout, but not the
                // Interval the runner was part-way through: those seconds were run.
                val saved = saveRunInterval(state)
                val skipped = saved.state.copy(
                    phase = RunPhase.COOL_DOWN,
                    phaseSecondsElapsed = 0,
                    phaseCarryMillis = -owed,
                    intervals = null,
                    intervalsFinished = true,
                    trigger = Trigger(),
                    turnaroundHeld = false,
                )
                RunOutcome(
                    skipped,
                    saved.effects + coolDownDropsWaitingCue(state) + listOf(
                        RunEffect.Speak("Starting cool down.", CuePriority.INSTRUCTION),
                        RunEffect.Notify(notificationText(skipped)),
                    ),
                )
            }
            RunPhase.COOL_DOWN -> finish(state, nowMillis)
        }
    }

    /** STOP, from the button, the notification, or the Run itself. */
    private fun stopped(state: RunState, event: RunEvent.Stopped): RunOutcome {
        // Nothing live to stop. STOP arrives from three places at once often enough that a second
        // one must be inert: finalizing twice would double the row write, the backup and the
        // coach's evaluation. Releasing the Strap is a separate act and stays outside — a STOP
        // with no Run to end still dismisses the service, and the Strap is #128's.
        if (!state.lifecycle.isLive) return RunOutcome(state)
        // Settle the clock to the tap first, as a pause or a skip does: the seconds run since the
        // last pulse are the runner's, and a STOP landing between ticks used to drop them from the
        // duration and the bands beneath it. A settle that ends the Run itself — the cool-down line
        // crossed by the same seconds — has already finalized, so there is nothing left to stop.
        val settled = accrue(state, event.nowMillis)
        if (!settled.state.lifecycle.isLive) return settled
        val ended = finish(settled.state, event.nowMillis)
        return RunOutcome(ended.state, settled.effects + ended.effects)
    }

    /**
     * End the Run, once.
     *
     * If the row id has arrived, finalization goes out now. If it has not, the Run is [STOPPING]
     * and finalization is *remembered* rather than lost — it is emitted the moment the id lands.
     * Those two orderings are the whole of the race: a STOP can never outrun its own row, and no
     * lock or flag is needed to say so.
     */
    private fun finish(state: RunState, nowMillis: Long): RunOutcome {
        // A Run stopped mid-Interval still banks it, and it is banked before the Run's own totals
        // so the two arrive in the order they happened.
        val saved = saveRunInterval(state)
        // A Run stopped while paused ends its Pause here, at the STOP, rather than leaving it open:
        // the runner never came back, so the far side of it is the end of the Run (#328).
        val closed = closeAnyPause(saved.state, saved.state, nowMillis)
        val current = closed.state
        val totals = RunTotals(
            durationSeconds = current.secondsRunning,
            pausedSeconds = current.secondsPaused,
            endedAtMillis = nowMillis,
            runType = current.config?.workout?.runType,
            averageBpm = current.tally.averageBpm,
            maxBpm = current.tally.maxBpm,
            zoneSeconds = current.tally.zoneSeconds,
            noDataSeconds = current.tally.noDataSeconds,
            effortScore = current.tally.effortScore,
            walkBreaks = current.walkBreaks,
        )
        val finalized = emitOrHold(current, PendingRowWork.Finalize(totals))
        val stopped =
            if (current.runRowId != null) RunLifecycle.STOPPED else RunLifecycle.STOPPING
        return RunOutcome(
            finalized.state.copy(lifecycle = stopped),
            saved.effects + closed.effects + RunEffect.StopGps + RunEffect.ReleaseStrap +
                finalized.effects,
        )
    }

    /**
     * What the locked phone says about the Run.
     *
     * The wording belongs to the Run (ADR 0001), so the notification cannot describe a Run the
     * accounting disagrees with. An Interval outranks the Phase: while the Workout is running, the
     * Interval is what the runner wants off a locked screen.
     */
    private fun notificationText(state: RunState): String {
        val intervals = state.intervals
        if (intervals != null && intervals.totalRepeats > 0) {
            return "Int ${intervals.repeat}/${intervals.totalRepeats} • " +
                "${intervals.kind.notificationName} • " +
                "${formatTime(intervals.secondsRemaining.coerceAtLeast(0).toLong())} left"
        }
        return phaseNotificationText(state)
    }

    private fun phaseNotificationText(state: RunState): String =
        if (state.phase == RunPhase.MAIN) {
            "${state.phase.notificationName} elapsed ${formatTime(state.phaseSecondsElapsed)}"
        } else {
            "${state.phase.notificationName} • ${formatTime(state.phaseSecondsRemaining.toLong())} left"
        }

    private fun formatTime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        return "%02d:%02d".format(safe / 60, safe % 60)
    }
}
