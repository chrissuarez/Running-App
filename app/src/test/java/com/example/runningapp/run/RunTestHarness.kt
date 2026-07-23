package com.example.runningapp.run

import com.example.runningapp.HrZone
import com.example.runningapp.WorkoutTemplate
import org.junit.Assert.assertEquals

/**
 * The one way the Run is exercised, shared by every test of it.
 *
 * Nothing here holds a rule: it feeds events to the entry point and keeps what comes back, exactly
 * as the service will. Every decision under test belongs to the Run.
 */
internal const val T0 = 1_700_000_000_000L

internal val PLANNED_WORKOUT = WorkoutTemplate(
    id = "w_test",
    title = "Test Workout",
    targetZone = 2,
    runDurationSeconds = 180,
    walkDurationSeconds = 60,
    totalRepeats = 6,
    warmUpSeconds = 60,
    coolDownSeconds = 30,
)

internal fun config(
    workout: WorkoutTemplate? = PLANNED_WORKOUT,
    runMode: RunMode = RunMode.TREADMILL,
    maxHr: Int = 190,
    targetZone: HrZone = HrZone.MODERATE,
    includeInAiTraining: Boolean = true,
) = RunConfig(
    maxHr = maxHr,
    targetZone = targetZone,
    runMode = runMode,
    workout = workout,
    includeInAiTraining = includeInAiTraining,
)

/**
 * Feeds events through the entry point and keeps the returned state, the way the service will.
 * It holds no rules of its own — every decision under test is the Run's.
 */
internal class Driver(var state: RunState = RunState.IDLE) {
    var nowMillis: Long = T0

    fun on(event: RunEvent): List<RunEffect> {
        val outcome = Run.onEvent(state, event)
        state = outcome.state
        return outcome.effects
    }

    /** START, plus the row id, unless [withRow] says to leave the Run waiting for it. */
    fun start(
        config: RunConfig = config(),
        controls: RunControls = RunControls(),
        withRow: Boolean = true,
        runRowId: Long = 7L,
    ): List<RunEffect> {
        val effects = on(RunEvent.Started(config, controls, nowMillis)).toMutableList()
        if (withRow) effects += on(RunEvent.RunRowCreated(runRowId, nowMillis))
        return effects
    }

    /** One tick per second, as the live app pulses. */
    fun advance(seconds: Int): List<RunEffect> {
        val effects = mutableListOf<RunEffect>()
        repeat(seconds) {
            nowMillis += 1_000
            effects += on(RunEvent.Tick(nowMillis))
        }
        return effects
    }

    /** One tick, arriving [seconds] late — the pulse the phone forgot to deliver. */
    fun advanceInOneTick(seconds: Int): List<RunEffect> {
        nowMillis += seconds * 1_000L
        return on(RunEvent.Tick(nowMillis))
    }

    fun stop(): List<RunEffect> = on(RunEvent.Stopped(nowMillis))

    fun skipPhase(): List<RunEffect> = on(RunEvent.PhaseSkipped(nowMillis))

    fun rowCreated(runRowId: Long = 7L): List<RunEffect> =
        on(RunEvent.RunRowCreated(runRowId, nowMillis))
}

internal inline fun <reified T : RunEffect> List<RunEffect>.only(): T {
    val matches = filterIsInstance<T>()
    assertEquals("expected exactly one ${T::class.simpleName} in $this", 1, matches.size)
    return matches.first()
}

internal inline fun <reified T : RunEffect> List<RunEffect>.count(): Int =
    filterIsInstance<T>().size

internal fun List<RunEffect>.spoken(): List<String> =
    filterIsInstance<RunEffect.Speak>().map { it.text }
