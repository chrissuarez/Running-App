package com.example.runningapp.run

import com.example.runningapp.HrZone
import com.example.runningapp.WorkoutTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Run, exercised the only way anything exercises it: events in, state and effects out.
 *
 * Every assertion goes through [Run.onEvent]. Nothing reaches inside for a field the Run does not
 * publish, nothing asserts on the order of internal calls, and nothing sleeps — time is an input,
 * so a test advances the clock by choosing the value it puts on a tick.
 *
 * The interleavings that broke `HrForegroundService` repeatedly get a named test each, so the next
 * rewrite cannot undo them silently.
 */
private const val T0 = 1_700_000_000_000L

private val PLANNED_WORKOUT = WorkoutTemplate(
    id = "w_test",
    title = "Test Workout",
    targetZone = 2,
    runDurationSeconds = 180,
    walkDurationSeconds = 60,
    totalRepeats = 6,
    warmUpSeconds = 60,
    coolDownSeconds = 30,
)

private fun config(
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
private class Driver(var state: RunState = RunState.IDLE) {
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

private inline fun <reified T : RunEffect> List<RunEffect>.only(): T {
    val matches = filterIsInstance<T>()
    assertEquals("expected exactly one ${T::class.simpleName} in $this", 1, matches.size)
    return matches.first()
}

private inline fun <reified T : RunEffect> List<RunEffect>.count(): Int =
    filterIsInstance<T>().size

private fun List<RunEffect>.spoken(): List<String> =
    filterIsInstance<RunEffect.Speak>().map { it.text }

class RunStartTest {

    @Test
    fun `starting a run asks for its row exactly once`() {
        val driver = Driver()
        val effects = driver.on(RunEvent.Started(config(), RunControls(), T0))

        val create = effects.only<RunEffect.CreateRunRow>()
        assertEquals(T0, create.startedAtMillis)
        assertEquals(RunLifecycle.RUNNING, driver.state.lifecycle)
    }

    @Test
    fun `the row request carries the settings pinned at start`() {
        val driver = Driver()
        val effects = driver.on(
            RunEvent.Started(
                config(runMode = RunMode.OUTDOOR, targetZone = HrZone.TEMPO, includeInAiTraining = false),
                RunControls(),
                T0,
            ),
        )

        val create = effects.only<RunEffect.CreateRunRow>()
        assertEquals(3, create.targetZoneNumber)
        assertEquals("outdoor", create.runModeSettingValue)
        assertFalse(create.includeInAiTraining)
    }

    @Test
    fun `a second start while the run is live asks for nothing`() {
        val driver = Driver()
        driver.start()
        driver.advance(5)

        val effects = driver.on(RunEvent.Started(config(), RunControls(), driver.nowMillis))

        assertTrue(effects.isEmpty())
        assertEquals(5L, driver.state.secondsRunning)
    }

    @Test
    fun `a start while the previous run is still waiting for its id is ignored`() {
        val driver = Driver()
        driver.start(withRow = false)
        driver.stop()

        val effects = driver.on(RunEvent.Started(config(), RunControls(), driver.nowMillis))

        assertTrue(effects.isEmpty())
        assertEquals(RunLifecycle.STOPPING, driver.state.lifecycle)
    }

    @Test
    fun `a new run keeps nothing from the one before it`() {
        val driver = Driver()
        driver.start()
        driver.advance(30)
        driver.stop()

        driver.nowMillis += 5_000
        driver.start(runRowId = 8L)

        assertEquals(0L, driver.state.secondsRunning)
        assertEquals(0L, driver.state.secondsPaused)
        assertEquals(RunPhase.WARM_UP, driver.state.phase)
        assertEquals(8L, driver.state.runRowId)
    }

    @Test
    fun `the run's settings are pinned for its lifetime`() {
        val driver = Driver()
        driver.start(config(maxHr = 190))
        driver.advance(10)
        driver.on(RunEvent.ControlsChanged(RunControls(coachingEnabled = false), driver.nowMillis))

        assertEquals(190, driver.state.config?.maxHr)
        assertEquals(HrZone.MODERATE, driver.state.config?.targetZone)
    }
}

/**
 * The creation window — the few hundred milliseconds between START and the row id coming back.
 * This is where `sessionCreationLock`, `stopDuringSessionCreation` and the post-commit gate used
 * to live, and where the #110 review loop found its interleavings.
 */
class RunRowHandshakeTest {

    @Test
    fun `stop after the row id arrives finalizes once, immediately`() {
        val driver = Driver()
        driver.start()
        driver.advance(12)

        val effects = driver.stop()

        val finalize = effects.only<RunEffect.FinalizeRun>()
        assertEquals(7L, finalize.runRowId)
        assertEquals(12L, finalize.totals.durationSeconds)
        assertEquals(RunLifecycle.STOPPED, driver.state.lifecycle)
    }

    @Test
    fun `stop before the row id arrives is remembered, not lost`() {
        val driver = Driver()
        driver.start(withRow = false)
        driver.advance(3)

        val effects = driver.stop()

        assertEquals(0, effects.count<RunEffect.FinalizeRun>())
        assertEquals(RunLifecycle.STOPPING, driver.state.lifecycle)
    }

    @Test
    fun `the remembered stop finalizes once when the id lands`() {
        val driver = Driver()
        driver.start(withRow = false)
        driver.advance(3)
        driver.stop()

        val effects = driver.rowCreated(runRowId = 42L)

        val finalize = effects.only<RunEffect.FinalizeRun>()
        assertEquals(42L, finalize.runRowId)
        assertEquals(3L, finalize.totals.durationSeconds)
        assertEquals(RunLifecycle.STOPPED, driver.state.lifecycle)
    }

    @Test
    fun `work held for the row id is flushed when it lands, and only then`() {
        val driver = Driver()
        driver.start(withRow = false)
        driver.advance(2)
        val stopEffects = driver.stop()

        val idEffects = driver.rowCreated()

        assertEquals(0, stopEffects.count<RunEffect.FinalizeRun>())
        assertEquals(listOf<RunEffect>(RunEffect.FinalizeRun(7L, driver.totalsOf())), idEffects)
    }

    @Test
    fun `held work is not flushed a second time`() {
        val driver = Driver()
        driver.start(withRow = false)
        driver.stop()
        driver.rowCreated()

        val effects = driver.rowCreated(runRowId = 8L)

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `a stop that arrived before the id does not start GPS when the id lands`() {
        val driver = Driver()
        driver.start(config(runMode = RunMode.OUTDOOR), withRow = false)
        driver.stop()

        val effects = driver.rowCreated()

        assertEquals(0, effects.count<RunEffect.StartGps>())
    }

    @Test
    fun `a second stop finalizes nothing`() {
        val driver = Driver()
        driver.start()
        driver.advance(4)
        driver.stop()

        val effects = driver.stop()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `a second stop before the id still finalizes only once`() {
        val driver = Driver()
        driver.start(withRow = false)
        driver.stop()
        driver.stop()

        val effects = driver.rowCreated()

        assertEquals(1, effects.count<RunEffect.FinalizeRun>())
    }

    @Test
    fun `a row id arriving twice is ignored`() {
        val driver = Driver()
        driver.start()

        val effects = driver.rowCreated(runRowId = 99L)

        assertTrue(effects.isEmpty())
        assertEquals(7L, driver.state.runRowId)
    }

    @Test
    fun `a row id arriving for a run that already finished is ignored`() {
        val driver = Driver()
        driver.start()
        driver.stop()

        val effects = driver.rowCreated(runRowId = 99L)

        assertTrue(effects.isEmpty())
        assertEquals(RunLifecycle.STOPPED, driver.state.lifecycle)
    }

    @Test
    fun `the run keeps its clock while it waits for its row id`() {
        val driver = Driver()
        driver.start(withRow = false)
        driver.advance(6)

        assertEquals(6L, driver.state.secondsRunning)
        assertNull(driver.state.runRowId)
    }

    @Test
    fun `an outdoor run starts GPS when the row id lands, not before`() {
        val driver = Driver()
        val startEffects = driver.on(
            RunEvent.Started(config(runMode = RunMode.OUTDOOR), RunControls(), T0),
        )
        assertEquals(0, startEffects.count<RunEffect.StartGps>())

        val idEffects = driver.rowCreated()

        assertEquals(1, idEffects.count<RunEffect.StartGps>())
    }

    @Test
    fun `a treadmill run never starts GPS`() {
        val driver = Driver()
        val effects = driver.start(config(runMode = RunMode.TREADMILL))

        assertEquals(0, effects.count<RunEffect.StartGps>())
    }
}

class RunClockTest {

    @Test
    fun `each second of wall clock is one second of run`() {
        val driver = Driver()
        driver.start()
        driver.advance(5)

        assertEquals(5L, driver.state.secondsRunning)
    }

    @Test
    fun `a tick arriving five seconds late advances five seconds`() {
        val driver = Driver()
        driver.start()

        driver.advanceInOneTick(5)

        assertEquals(5L, driver.state.secondsRunning)
    }

    @Test
    fun `a late tick advances the same seconds as the individual ticks would have`() {
        val steady = Driver().apply { start(); advance(12) }
        val late = Driver().apply { start(); advanceInOneTick(12) }

        assertEquals(steady.state.secondsRunning, late.state.secondsRunning)
        assertEquals(steady.state.phase, late.state.phase)
        assertEquals(steady.state.phaseSecondsElapsed, late.state.phaseSecondsElapsed)
    }

    @Test
    fun `a tick less than a second after the last one changes nothing`() {
        val driver = Driver()
        driver.start()

        driver.nowMillis += 400
        val effects = driver.on(RunEvent.Tick(driver.nowMillis))

        assertTrue(effects.isEmpty())
        assertEquals(0L, driver.state.secondsRunning)
    }

    @Test
    fun `a pulse banks whole seconds and drops the remainder, as it always has`() {
        val driver = Driver()
        driver.start()

        // Ten pulses of 1,100ms is eleven seconds of wall clock but ten seconds of Run: each
        // pulse banks one second and forgets the other 100ms. Carrying the remainder would be a
        // change to every recorded duration, so it is not made here.
        repeat(10) {
            driver.nowMillis += 1_100
            driver.on(RunEvent.Tick(driver.nowMillis))
        }

        assertEquals(10L, driver.state.secondsRunning)
    }

    @Test
    fun `ticks before the run starts do nothing`() {
        val driver = Driver()

        val effects = driver.advance(3)

        assertTrue(effects.isEmpty())
        assertEquals(0L, driver.state.secondsRunning)
    }

    @Test
    fun `a tick already in flight when the run stops banks nothing`() {
        val driver = Driver()
        driver.start()
        driver.advance(9)
        driver.stop()

        val effects = driver.advance(4)

        assertTrue(effects.isEmpty())
        assertEquals(9L, driver.state.secondsRunning)
    }

    @Test
    fun `a tick landing while the run waits to finalize banks nothing`() {
        val driver = Driver()
        driver.start(withRow = false)
        driver.advance(9)
        driver.stop()

        driver.advance(4)

        assertEquals(9L, driver.state.secondsRunning)
    }
}

class RunPhaseTest {

    @Test
    fun `warm-up gives its ten second warning`() {
        val driver = Driver()
        driver.start()

        val effects = driver.advance(50)

        assertEquals(listOf("10 seconds of warm up remaining"), effects.spoken())
    }

    @Test
    fun `warm-up hands over to main`() {
        val driver = Driver()
        driver.start()

        val effects = driver.advance(60)

        assertTrue(effects.spoken().contains("Starting main workout"))
        assertEquals(RunPhase.MAIN, driver.state.phase)
        assertEquals(0L, driver.state.phaseSecondsElapsed)
        assertEquals(60L, driver.state.secondsRunning)
    }

    @Test
    fun `a phase boundary inside a catch-up is still honoured`() {
        val driver = Driver()
        driver.start()

        val effects = driver.advanceInOneTick(65)

        assertEquals(
            listOf("10 seconds of warm up remaining", "Starting main workout"),
            effects.spoken(),
        )
        assertEquals(RunPhase.MAIN, driver.state.phase)
        assertEquals(5L, driver.state.phaseSecondsElapsed)
    }

    @Test
    fun `the main phase is open ended`() {
        val driver = Driver()
        driver.start()
        driver.advance(60)

        driver.advance(600)

        assertEquals(RunPhase.MAIN, driver.state.phase)
        assertEquals(RunLifecycle.RUNNING, driver.state.lifecycle)
    }

    @Test
    fun `a skipped warm-up hands over to main at once and is remembered`() {
        val driver = Driver()
        driver.start()
        driver.advance(5)

        val effects = driver.skipPhase()

        assertTrue(effects.spoken().contains("Warm up skipped. Starting workout."))
        assertEquals(RunPhase.MAIN, driver.state.phase)
        assertTrue(driver.state.warmUpSkipped)
        assertEquals(0L, driver.state.phaseSecondsElapsed)
    }

    @Test
    fun `a skipped warm-up says nothing more about the warm-up`() {
        val driver = Driver()
        driver.start()
        driver.skipPhase()

        val effects = driver.advance(120)

        assertFalse(effects.spoken().contains("10 seconds of warm up remaining"))
        assertFalse(effects.spoken().contains("Starting main workout"))
    }

    @Test
    fun `skipping main begins the cool down`() {
        val driver = Driver()
        driver.start()
        driver.advance(60)

        val effects = driver.skipPhase()

        assertTrue(effects.spoken().contains("Starting cool down."))
        assertEquals(RunPhase.COOL_DOWN, driver.state.phase)
        assertEquals(0L, driver.state.phaseSecondsElapsed)
    }

    @Test
    fun `the cool down gives its ten second warning`() {
        val driver = Driver()
        driver.start()
        driver.advance(60)
        driver.skipPhase()

        val effects = driver.advance(20)

        assertEquals(listOf("10 seconds of cool down remaining"), effects.spoken())
    }

    @Test
    fun `the cool down completing ends the run by itself`() {
        val driver = Driver()
        driver.start()
        driver.advance(60)
        driver.skipPhase()

        val effects = driver.advance(30)

        assertEquals(1, effects.count<RunEffect.FinalizeRun>())
        assertEquals(RunLifecycle.STOPPED, driver.state.lifecycle)
    }

    @Test
    fun `the cool down ending finalizes with the run's own totals`() {
        val driver = Driver()
        driver.start()
        driver.advance(60)
        driver.skipPhase()
        val effects = driver.advance(30)

        val finalize = effects.only<RunEffect.FinalizeRun>()
        assertEquals(90L, finalize.totals.durationSeconds)
    }

    @Test
    fun `the cool down ending stops the clock`() {
        val driver = Driver()
        driver.start()
        driver.advance(60)
        driver.skipPhase()
        driver.advance(30)

        driver.advance(10)

        assertEquals(90L, driver.state.secondsRunning)
    }

    @Test
    fun `skipping the cool down ends the run`() {
        val driver = Driver()
        driver.start()
        driver.advance(60)
        driver.skipPhase()

        val effects = driver.skipPhase()

        assertEquals(1, effects.count<RunEffect.FinalizeRun>())
        assertEquals(RunLifecycle.STOPPED, driver.state.lifecycle)
    }

    @Test
    fun `an unplanned run has no warm-up to wait for`() {
        val driver = Driver()
        driver.start(config(workout = null))

        driver.advance(1)

        assertEquals(RunPhase.MAIN, driver.state.phase)
    }

    @Test
    fun `an unplanned run announces the main workout on its first second`() {
        val driver = Driver()
        driver.start(config(workout = null))

        val effects = driver.advance(1)

        // Odd, and deliberately kept: a nought-second warm-up still hands over out loud. Changing
        // what the Run says is not this move's to do.
        assertEquals(listOf("Starting main workout"), effects.spoken())
    }

    @Test
    fun `an unplanned run never reaches a cool down on its own`() {
        val driver = Driver()
        driver.start(config(workout = null))

        driver.advance(3_600)

        assertEquals(RunPhase.MAIN, driver.state.phase)
        assertEquals(RunLifecycle.RUNNING, driver.state.lifecycle)
    }

    @Test
    fun `a skip after the run stops does nothing`() {
        val driver = Driver()
        driver.start()
        driver.stop()

        val effects = driver.skipPhase()

        assertTrue(effects.isEmpty())
    }
}

class RunPauseTest {

    @Test
    fun `paused seconds accrue and running seconds do not`() {
        val driver = Driver()
        driver.start()
        driver.advance(10)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        driver.advance(7)

        assertEquals(10L, driver.state.secondsRunning)
        assertEquals(7L, driver.state.secondsPaused)
    }

    @Test
    fun `resume returns to the phase it left`() {
        val driver = Driver()
        driver.start()
        driver.advance(60)
        driver.advance(20)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.advance(30)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        driver.advance(5)

        assertEquals(RunPhase.MAIN, driver.state.phase)
        assertEquals(25L, driver.state.phaseSecondsElapsed)
        assertEquals(85L, driver.state.secondsRunning)
        assertEquals(30L, driver.state.secondsPaused)
    }

    @Test
    fun `a tapped pause stops GPS and a resume starts it again`() {
        val driver = Driver()
        driver.start(config(runMode = RunMode.OUTDOOR))

        val paused = driver.on(RunEvent.PauseToggled(driver.nowMillis))
        val resumed = driver.on(RunEvent.PauseToggled(driver.nowMillis))

        assertEquals(1, paused.count<RunEffect.StopGps>())
        assertEquals(1, resumed.count<RunEffect.StartGps>())
    }

    @Test
    fun `a treadmill run resumes without asking for GPS`() {
        val driver = Driver()
        driver.start(config(runMode = RunMode.TREADMILL))
        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        val resumed = driver.on(RunEvent.PauseToggled(driver.nowMillis))

        assertEquals(0, resumed.count<RunEffect.StartGps>())
    }

    @Test
    fun `an auto-pause keeps GPS running, because movement is how it resumes`() {
        val driver = Driver()
        driver.start(config(runMode = RunMode.OUTDOOR))

        val effects = driver.on(RunEvent.AutoPauseRequested(driver.nowMillis))

        assertEquals(0, effects.count<RunEffect.StopGps>())
        assertTrue(effects.spoken().contains("Auto-paused."))
        assertEquals(RunLifecycle.PAUSED, driver.state.lifecycle)
    }

    @Test
    fun `only an auto-pause may be auto-resumed`() {
        val driver = Driver()
        driver.start()
        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        val effects = driver.on(RunEvent.AutoResumeRequested(driver.nowMillis))

        assertTrue(effects.isEmpty())
        assertEquals(RunLifecycle.PAUSED, driver.state.lifecycle)
    }

    @Test
    fun `an auto-resume restarts an auto-paused run`() {
        val driver = Driver()
        driver.start()
        driver.on(RunEvent.AutoPauseRequested(driver.nowMillis))

        val effects = driver.on(RunEvent.AutoResumeRequested(driver.nowMillis))

        assertTrue(effects.spoken().contains("Resuming."))
        assertEquals(RunLifecycle.RUNNING, driver.state.lifecycle)
        assertFalse(driver.state.autoPaused)
    }

    @Test
    fun `a manual resume clears an auto-pause`() {
        val driver = Driver()
        driver.start()
        driver.on(RunEvent.AutoPauseRequested(driver.nowMillis))

        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        assertEquals(RunLifecycle.RUNNING, driver.state.lifecycle)
        assertFalse(driver.state.autoPaused)
    }

    @Test
    fun `a stale pause landing after the run stops is ignored`() {
        val driver = Driver()
        driver.start()
        driver.stop()

        val effects = driver.on(RunEvent.PauseToggled(driver.nowMillis))

        assertTrue(effects.isEmpty())
        assertEquals(RunLifecycle.STOPPED, driver.state.lifecycle)
    }

    @Test
    fun `a stale resume landing after the run stops does not revive it`() {
        val driver = Driver()
        driver.start()
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.stop()

        val effects = driver.on(RunEvent.PauseToggled(driver.nowMillis))

        assertTrue(effects.isEmpty())
        assertEquals(RunLifecycle.STOPPED, driver.state.lifecycle)
    }

    @Test
    fun `a paused run stops with the seconds it actually ran`() {
        val driver = Driver()
        driver.start()
        driver.advance(20)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.advance(45)

        val finalize = driver.stop().only<RunEffect.FinalizeRun>()

        assertEquals(20L, finalize.totals.durationSeconds)
        assertEquals(45L, finalize.totals.pausedSeconds)
    }
}

class RunTotalsTest {

    @Test
    fun `the finished run's totals are the numbers the run counted`() {
        val driver = Driver()
        driver.start()
        driver.advance(30)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.advance(10)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.advance(15)

        val finalize = driver.stop().only<RunEffect.FinalizeRun>()

        assertEquals(45L, finalize.totals.durationSeconds)
        assertEquals(10L, finalize.totals.pausedSeconds)
        assertEquals(driver.nowMillis, finalize.totals.endedAtMillis)
        assertTrue(finalize.totals.isRunWalkMode)
    }

    @Test
    fun `an unplanned run is not recorded as a run-walk run`() {
        val driver = Driver()
        driver.start(config(workout = null))
        driver.advance(5)

        val finalize = driver.stop().only<RunEffect.FinalizeRun>()

        assertFalse(finalize.totals.isRunWalkMode)
    }

    @Test
    fun `stopping asks for no read of the run's row`() {
        val driver = Driver()
        driver.start()
        driver.advance(5)

        val effects = driver.stop()

        // The whole finalization: hand back GPS, write the totals. Nothing goes looking for the
        // row to work out what to write.
        assertEquals(
            listOf(RunEffect.StopGps, RunEffect.FinalizeRun(7L, driver.totalsOf(durationSeconds = 5))),
            effects,
        )
    }
}

class RunControlsTest {

    @Test
    fun `coaching turned off mid-run arrives as an event`() {
        val driver = Driver()
        driver.start(controls = RunControls(coachingEnabled = true))
        driver.advance(10)

        driver.on(RunEvent.ControlsChanged(RunControls(coachingEnabled = false), driver.nowMillis))

        assertFalse(driver.state.controls.coachingEnabled)
    }

    @Test
    fun `auto-pause and split announcements arrive the same way`() {
        val driver = Driver()
        driver.start(controls = RunControls(autoPauseEnabled = false, splitAnnouncementsEnabled = false))

        driver.on(
            RunEvent.ControlsChanged(
                RunControls(autoPauseEnabled = true, splitAnnouncementsEnabled = true),
                driver.nowMillis,
            ),
        )

        assertTrue(driver.state.controls.autoPauseEnabled)
        assertTrue(driver.state.controls.splitAnnouncementsEnabled)
    }

    @Test
    fun `changing a control is not itself something to do`() {
        val driver = Driver()
        driver.start()

        val effects = driver.on(RunEvent.ControlsChanged(RunControls(), driver.nowMillis))

        assertTrue(effects.isEmpty())
    }
}

class RunNotificationTest {

    @Test
    fun `the run offers the notification one refresh per pulse`() {
        val driver = Driver()
        driver.start()

        val effects = driver.advance(10)

        assertEquals(10, effects.count<RunEffect.Notify>())
    }

    @Test
    fun `a pulse that caught up several seconds still offers one refresh`() {
        val driver = Driver()
        driver.start()

        val effects = driver.advanceInOneTick(10)

        assertEquals(1, effects.count<RunEffect.Notify>())
    }

    @Test
    fun `the warm-up notification counts down`() {
        val driver = Driver()
        driver.start()

        val effects = driver.advanceInOneTick(10)

        assertEquals("Warm-up • 00:50 left", effects.only<RunEffect.Notify>().text)
    }

    @Test
    fun `the main notification counts up`() {
        val driver = Driver()
        driver.start()
        driver.advance(60)

        val effects = driver.advanceInOneTick(10)

        assertEquals("Main elapsed 00:10", effects.only<RunEffect.Notify>().text)
    }

    @Test
    fun `the notification follows the run into its next phase`() {
        val driver = Driver()
        driver.start()

        val effects = driver.advance(59) + driver.advance(1)

        assertEquals("Main elapsed 00:00", effects.filterIsInstance<RunEffect.Notify>().last().text)
    }

    @Test
    fun `a skipped phase refreshes the notification at once`() {
        val driver = Driver()
        driver.start()
        driver.advance(5)

        val effects = driver.skipPhase()

        assertEquals("Main elapsed 00:00", effects.only<RunEffect.Notify>().text)
    }

    @Test
    fun `a stopped run posts no notification`() {
        val driver = Driver()
        driver.start()
        driver.advance(5)

        val effects = driver.stop() + driver.advance(20)

        assertEquals(0, effects.count<RunEffect.Notify>())
    }
}

/** The totals a Run in this Driver's state would be finalized with. */
private fun Driver.totalsOf(
    durationSeconds: Long = state.secondsRunning,
    pausedSeconds: Long = state.secondsPaused,
) = RunTotals(
    durationSeconds = durationSeconds,
    pausedSeconds = pausedSeconds,
    endedAtMillis = nowMillis,
    isRunWalkMode = state.config?.isRunWalkMode ?: false,
)
