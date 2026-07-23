package com.example.runningapp.run

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
        is RunEvent.ControlsChanged -> RunOutcome(state.copy(controls = event.controls))
        is RunEvent.PauseToggled -> pauseToggled(state)
        is RunEvent.AutoPauseRequested -> autoPause(state)
        is RunEvent.AutoResumeRequested -> autoResume(state)
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
        if (!stopping && state.config?.runMode == RunMode.OUTDOOR) {
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
     * The sub-second remainder is dropped rather than carried, which is what the pulse it replaces
     * did. Carrying it would make the Run's clock track the wall slightly more closely, and that is
     * a change to every recorded duration — not this move's to make.
     */
    private fun tick(state: RunState, event: RunEvent.Tick): RunOutcome {
        if (!state.lifecycle.isLive) return RunOutcome(state)
        val elapsedSeconds = (event.nowMillis - state.lastTickMillis) / 1000
        if (elapsedSeconds < 1) return RunOutcome(state)
        val accounted = state.copy(lastTickMillis = event.nowMillis)
        return when (accounted.lifecycle) {
            RunLifecycle.PAUSED -> RunOutcome(
                accounted.copy(secondsPaused = accounted.secondsPaused + elapsedSeconds),
            )
            else -> advanceRunningSeconds(accounted, elapsedSeconds, event.nowMillis)
        }
    }

    /**
     * The per-second accounting, one second at a time even when catching up, so a Phase boundary
     * that falls inside the gap is honoured at the second it belongs to rather than jumped over.
     */
    private fun advanceRunningSeconds(state: RunState, seconds: Long, nowMillis: Long): RunOutcome {
        var current = state
        val effects = mutableListOf<RunEffect>()
        var ended = false

        for (i in 1..seconds) {
            current = current.copy(
                secondsRunning = current.secondsRunning + 1,
                phaseSecondsElapsed = current.phaseSecondsElapsed + 1,
            )
            // Intervals, zone accounting and the coach's cue decisions belong on this second too.
            // They are the next two tickets; this is where they land.
            val limit = current.phaseLimitSeconds
            val remaining = limit - current.phaseSecondsElapsed

            if (current.phase != RunPhase.MAIN && remaining == 10L) {
                effects += RunEffect.Speak("10 seconds of ${current.phase.spokenName} remaining")
            }

            // A Run with no Workout has a nought-second warm-up, so it hands over on its first
            // second — announcing the main workout to a runner who was never told to warm up. That
            // is what the Run does today, and this move is not the place to change what it says.
            if (current.phase == RunPhase.WARM_UP && current.phaseSecondsElapsed >= limit) {
                current = current.copy(phase = RunPhase.MAIN, phaseSecondsElapsed = 0)
                effects += RunEffect.Speak("Starting main workout")
            } else if (current.phase == RunPhase.COOL_DOWN && current.phaseSecondsElapsed >= limit) {
                // The Run ends itself. Nothing calls back in to stop it — that round trip was one
                // more ordering for the service to get right.
                val end = finish(current, nowMillis)
                current = end.state
                effects += end.effects
                ended = true
                break
            }
        }

        // One refresh per pulse, however many seconds it accounted for, and none for a Run that
        // just ended. How often that reaches the platform is the performer's business: it already
        // throttles a backgrounded notification, which turns on whether an activity is bound —
        // an Android fact the Run has no way of knowing and no reason to.
        if (!ended) effects += RunEffect.Notify(notificationText(current))
        return RunOutcome(current, effects)
    }

    /** The pause/resume button. Only a live Run can be paused, and only a paused one resumed. */
    private fun pauseToggled(state: RunState): RunOutcome = when (state.lifecycle) {
        RunLifecycle.RUNNING -> RunOutcome(
            state.copy(lifecycle = RunLifecycle.PAUSED, autoPaused = false),
            // A tapped pause stops GPS; an auto-pause deliberately does not.
            listOf(RunEffect.StopGps, RunEffect.Notify(notificationText(state))),
        )
        RunLifecycle.PAUSED -> {
            val resumed = state.copy(lifecycle = RunLifecycle.RUNNING, autoPaused = false)
            val effects = buildList {
                // The mode the Run started with, not whatever the setting says now.
                if (state.config?.runMode == RunMode.OUTDOOR) add(RunEffect.StartGps)
                add(RunEffect.Notify(notificationText(resumed)))
            }
            RunOutcome(resumed, effects)
        }
        // A stale notification action — the shade lags the Run — must not flip a stopped Run back
        // to paused, nor republish a running one over a Run that is already being finalized.
        else -> RunOutcome(state)
    }

    /**
     * A sustained standstill (#39). Freezes the clock exactly like a tapped pause, but keeps GPS.
     *
     * Unconditional on [RunControls.autoPauseEnabled]: the standstill is detected by the module
     * that watches the fixes, and that is where the toggle is honoured. A request that reaches
     * here has already been decided.
     */
    private fun autoPause(state: RunState): RunOutcome {
        if (state.lifecycle != RunLifecycle.RUNNING) return RunOutcome(state)
        val paused = state.copy(lifecycle = RunLifecycle.PAUSED, autoPaused = true)
        return RunOutcome(
            paused,
            listOf(RunEffect.Notify(notificationText(paused)), RunEffect.Speak("Auto-paused.")),
        )
    }

    /** Movement again. Only an auto-pause may be undone this way; a tapped pause is the runner's. */
    private fun autoResume(state: RunState): RunOutcome {
        if (state.lifecycle != RunLifecycle.PAUSED || !state.autoPaused) return RunOutcome(state)
        val resumed = state.copy(lifecycle = RunLifecycle.RUNNING, autoPaused = false)
        return RunOutcome(
            resumed,
            listOf(RunEffect.Notify(notificationText(resumed)), RunEffect.Speak("Resuming.")),
        )
    }

    /** The skip button. Each Phase hands over to the next; skipping the cool-down ends the Run. */
    private fun phaseSkipped(state: RunState, event: RunEvent.PhaseSkipped): RunOutcome {
        if (!state.lifecycle.isLive) return RunOutcome(state)
        return when (state.phase) {
            RunPhase.WARM_UP -> {
                val skipped = state.copy(
                    phase = RunPhase.MAIN,
                    phaseSecondsElapsed = 0,
                    warmUpSkipped = true,
                )
                RunOutcome(
                    skipped,
                    listOf(
                        RunEffect.Speak("Warm up skipped. Starting workout."),
                        RunEffect.Notify(notificationText(skipped)),
                    ),
                )
            }
            RunPhase.MAIN -> {
                val skipped = state.copy(phase = RunPhase.COOL_DOWN, phaseSecondsElapsed = 0)
                RunOutcome(
                    skipped,
                    listOf(
                        RunEffect.Speak("Starting cool down."),
                        RunEffect.Notify(notificationText(skipped)),
                    ),
                )
            }
            RunPhase.COOL_DOWN -> finish(state, event.nowMillis)
        }
    }

    /** STOP, from the button, the notification, or the Run itself. */
    private fun stopped(state: RunState, event: RunEvent.Stopped): RunOutcome {
        // Nothing live to stop. STOP arrives from three places at once often enough that a second
        // one must be inert: finalizing twice would double the row write, the backup and the
        // coach's evaluation. Releasing the Strap is a separate act and stays outside — a STOP
        // with no Run to end still dismisses the service, and the Strap is #128's.
        if (!state.lifecycle.isLive) return RunOutcome(state)
        return finish(state, event.nowMillis)
    }

    /**
     * End the Run, once.
     *
     * If the row id has arrived, finalization goes out now. If it has not, the Run is [STOPPING]
     * and finalization is *remembered* rather than lost — it is emitted the moment the id lands.
     * Those two orderings are the whole of what `sessionCreationLock`, `stopDuringSessionCreation`
     * and the post-commit gate were spelling between them.
     */
    private fun finish(state: RunState, nowMillis: Long): RunOutcome {
        val totals = RunTotals(
            durationSeconds = state.secondsRunning,
            pausedSeconds = state.secondsPaused,
            endedAtMillis = nowMillis,
            isRunWalkMode = state.config?.isRunWalkMode ?: false,
        )
        val runRowId = state.runRowId
        return if (runRowId != null) {
            RunOutcome(
                state.copy(lifecycle = RunLifecycle.STOPPED),
                listOf(RunEffect.StopGps, RunEffect.FinalizeRun(runRowId, totals)),
            )
        } else {
            RunOutcome(
                state.copy(
                    lifecycle = RunLifecycle.STOPPING,
                    pendingRowEffects = state.pendingRowEffects + PendingRowWork.Finalize(totals),
                ),
                listOf(RunEffect.StopGps),
            )
        }
    }

    /**
     * What the locked phone says about the Run.
     *
     * The wording belongs to the Run (ADR 0001), so the notification cannot describe a Run the
     * accounting disagrees with. The Interval line — "Int 3/6 • RUN • 01:20 left" — lands here
     * with the Intervals themselves.
     */
    private fun notificationText(state: RunState): String =
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
