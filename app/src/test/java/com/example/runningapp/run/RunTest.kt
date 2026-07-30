package com.example.runningapp.run

import com.example.runningapp.HrProfile
import com.example.runningapp.HrZone
import com.example.runningapp.RunType
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
 *
 * The [Driver] and the fixtures live in `RunTestHarness.kt`, shared with the Interval tests.
 */

/**
 * A Workout small enough to run end to end in a test and reach its cool-down: two 2-second run
 * Intervals with no walk between them, a 2-second warm-up, and a 30-second cool-down. Completing
 * the last Interval lands at the fifth second; the cool-down then runs to the thirty-fifth.
 */
private val COOLDOWN_WORKOUT = WorkoutTemplate(
    id = "w_cooldown",
    title = "Cool-down",
    targetZone = 2,
    runDurationSeconds = 2,
    walkDurationSeconds = 0,
    totalRepeats = 2,
    warmUpSeconds = 2,
    coolDownSeconds = 30,
    runType = RunType.LONG,
)

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
        driver.start(config(hrProfile = HrProfile(190)))
        driver.advance(10)
        driver.on(RunEvent.ControlsChanged(RunControls(coachingEnabled = false), driver.nowMillis))

        assertEquals(HrProfile(190), driver.state.config?.hrProfile)
        assertEquals(HrZone.MODERATE, driver.state.config?.targetZone)
    }
}

/**
 * The creation window — the few hundred milliseconds between START and the row id coming back.
 * Every interleaving the #110 review loop found, asserted here rather than guarded by a lock.
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

    @Test
    fun `a run paused before its row id arrives does not start GPS when the id lands`() {
        val driver = Driver()
        driver.start(config(runMode = RunMode.OUTDOOR), withRow = false)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        val effects = driver.rowCreated()

        assertEquals(0, effects.count<RunEffect.StartGps>())
    }

    @Test
    fun `that paused run starts GPS when it is resumed`() {
        val driver = Driver()
        driver.start(config(runMode = RunMode.OUTDOOR), withRow = false)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.rowCreated()

        val resumed = driver.on(RunEvent.PauseToggled(driver.nowMillis))

        assertEquals(1, resumed.count<RunEffect.StartGps>())
    }

    @Test
    fun `a run resumed before its row id arrives starts GPS once, when the id lands`() {
        val driver = Driver()
        driver.start(config(runMode = RunMode.OUTDOOR), withRow = false)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        val resumed = driver.on(RunEvent.PauseToggled(driver.nowMillis))
        val idEffects = driver.rowCreated()

        assertEquals("no GPS with nowhere to put it", 0, resumed.count<RunEffect.StartGps>())
        assertEquals("started once, when the id lands", 1, idEffects.count<RunEffect.StartGps>())
    }

    @Test
    fun `a treadmill run starts no GPS through any pause and resume in the row window`() {
        val driver = Driver()
        driver.start(config(runMode = RunMode.TREADMILL), withRow = false)

        val paused = driver.on(RunEvent.PauseToggled(driver.nowMillis))
        val resumed = driver.on(RunEvent.PauseToggled(driver.nowMillis))
        val idEffects = driver.rowCreated()

        assertEquals(0, (paused + resumed + idEffects).count<RunEffect.StartGps>())
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
    fun `the clock carries the sub-second remainder instead of dropping it`() {
        val driver = Driver()
        driver.start()

        // A single pulse of 1,900ms advances one second and keeps the leftover 900ms owed, so the
        // next 100ms pulse crosses the second line the old clock would have thrown away.
        driver.tickAfter(1_900)
        assertEquals(1L, driver.state.secondsRunning)

        driver.tickAfter(100)
        assertEquals(2L, driver.state.secondsRunning)
    }

    @Test
    fun `slightly-over-a-second ticks accumulate to the wall clock with no drift`() {
        val driver = Driver()
        driver.start(config(workout = null))

        // The pulses land a shade over a second apart. A hundred of them at 1,100ms is 110 seconds
        // of wall clock; the Run's clock now matches it rather than reading 100 and running slow.
        repeat(100) { driver.tickAfter(1_100) }

        assertEquals(110L, driver.state.secondsRunning)
    }

    @Test
    fun `a tick arriving several seconds late still advances exactly that many seconds`() {
        val driver = Driver()
        driver.start()

        // Carrying the remainder does not blur the catch-up: a late pulse still advances whole
        // seconds, one at a time.
        driver.tickAfter(5_400)

        assertEquals(5L, driver.state.secondsRunning)
    }

    @Test
    fun `paused seconds carry the remainder on the same basis as running seconds`() {
        val driver = Driver()
        driver.start()
        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        repeat(100) { driver.tickAfter(1_100) }

        assertEquals(110L, driver.state.secondsPaused)
    }

    @Test
    fun `a pause landing between ticks does not bank the running remainder as paused`() {
        val driver = Driver()
        driver.start()

        // A 1,900ms running tick banks one second and leaves 900ms owed — all of it run.
        driver.tickAfter(1_900)
        assertEquals(1L, driver.state.secondsRunning)

        // Pause on that same pulse, then a tick 100ms on. Without settling, the next tick would
        // measure 1,000ms from the old boundary and bank a paused second that was 900ms run; the
        // owed 900ms is set aside against the running stretch, so no paused second is banked.
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.tickAfter(100)

        assertEquals(0L, driver.state.secondsPaused)
        assertEquals(1L, driver.state.secondsRunning)
    }

    @Test
    fun `a whole second run before a pause is banked as running, not lost`() {
        val driver = Driver()
        driver.start()

        // One second banked, then a full second more elapses before the pause arrives — a pulse the
        // phone was slow to deliver. That second was run, so pausing must bank it as running.
        driver.tickAfter(1_000)
        assertEquals(1L, driver.state.secondsRunning)

        driver.nowMillis += 1_000
        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        assertEquals(2L, driver.state.secondsRunning)
        assertEquals(0L, driver.state.secondsPaused)
    }

    @Test
    fun `the running remainder survives a pause and completes its second on resume`() {
        val driver = Driver()
        driver.start()

        // 900ms owed as running, then a pause, 700ms of pause, a resume, and 100ms more of running.
        driver.tickAfter(1_900)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.nowMillis += 700
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.tickAfter(100)

        // The 900ms run before the pause plus the 100ms after it make the second the pause split.
        assertEquals(2L, driver.state.secondsRunning)
        // 700ms of pause is under a second, so nothing is banked as paused yet either.
        assertEquals(0L, driver.state.secondsPaused)
    }

    @Test
    fun `a second run before a skip belongs to the phase being left`() {
        val driver = Driver()
        driver.start()

        // A 1,900ms pulse banks one warm-up second and leaves 900ms owed.
        driver.tickAfter(1_900)

        // The skip lands 100ms later, a full second after the last accounted boundary. Without
        // settling first, the next tick would measure 2,000ms from that boundary and spend two
        // seconds on the new phase — one of them run before the skip.
        driver.nowMillis += 100
        driver.skipPhase()
        driver.tickAfter(1_000)

        assertEquals(3L, driver.state.secondsRunning)
        assertEquals(1L, driver.state.phaseSecondsElapsed)
    }

    @Test
    fun `a phase entered part-way through a second owes that fraction before its first second`() {
        val driver = Driver()
        driver.start()

        // A 1,900ms pulse banks one warm-up second and leaves 900ms owed to the warm-up. Skipping
        // on that pulse must not spend those 900ms on the main phase.
        driver.tickAfter(1_900)
        driver.skipPhase()

        // The next run second falls only 100ms after the skip, so the main phase has yet to run one.
        driver.tickAfter(1_000)
        assertEquals(2L, driver.state.secondsRunning)
        assertEquals(0L, driver.state.phaseSecondsElapsed)

        // A second later it has — the run's clock never stops, only the credit moves.
        driver.tickAfter(1_000)
        assertEquals(3L, driver.state.secondsRunning)
        assertEquals(1L, driver.state.phaseSecondsElapsed)
    }

    @Test
    fun `a skip taken while paused still owes the fraction run before the pause`() {
        val driver = Driver()
        driver.start()

        // 900ms owed to the warm-up, parked by the pause. Skipping while paused, then resuming,
        // must leave the main phase owing those 900ms rather than starting on the resumed clock.
        driver.tickAfter(1_900)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.skipPhase()
        driver.nowMillis += 5_000
        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        // The first running second after the resume comes 100ms on — the fraction the pause parked.
        driver.tickAfter(100)
        assertEquals(2L, driver.state.secondsRunning)
        assertEquals(0L, driver.state.phaseSecondsElapsed)

        driver.tickAfter(1_000)
        assertEquals(1L, driver.state.phaseSecondsElapsed)
    }

    @Test
    fun `a skip settling past its own phase line does not skip the next phase too`() {
        val driver = Driver()
        driver.start()

        // The 60-second warm-up's last second arrives with the tap rather than before it: settling
        // hands the Run into the main phase, so "Skip Warm Up" has nothing left to skip. Skipping
        // again from there would abandon the whole workout.
        driver.advance(59)
        driver.nowMillis += 1_000
        driver.skipPhase()

        assertEquals(RunPhase.MAIN, driver.state.phase)
        assertFalse(driver.state.intervalsFinished)
    }

    @Test
    fun `a stop banks the seconds run since the last pulse`() {
        val driver = Driver()
        driver.start()

        // One second banked, then a full second more before the STOP — a pulse the phone was slow
        // to deliver. Those seconds were run, so the saved duration must count them.
        driver.tickAfter(1_000)
        driver.nowMillis += 1_000

        val finalize = driver.on(RunEvent.Stopped(driver.nowMillis)).only<RunEffect.FinalizeRun>()

        assertEquals(2L, finalize.totals.durationSeconds)
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
            listOf(
                "10 seconds of warm up remaining",
                "Starting main workout",
                "Start running, interval 1 of 6.",
            ),
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
    fun `the cool down completing releases the strap`() {
        // A Run that ends itself does not go through the service's STOP, so releasing the strap
        // and audio session has to ride out on an effect or it never happens (the leak this guards).
        val driver = Driver()
        driver.start()
        driver.advance(60)
        driver.skipPhase()

        val effects = driver.advance(30)

        assertEquals(1, effects.count<RunEffect.ReleaseStrap>())
    }

    @Test
    fun `a stop releases the strap`() {
        // The same choke point serves the button and notification STOP, so both end paths let go
        // of the strap through one effect. Moving release out of finish() breaks this.
        val driver = Driver()
        driver.start()
        driver.advance(5)

        val effects = driver.stop()

        assertEquals(1, effects.count<RunEffect.ReleaseStrap>())
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
    fun `completing the last interval hands the run into its cool-down`() {
        val driver = Driver()
        driver.start(config(workout = COOLDOWN_WORKOUT))

        val effects = driver.advance(5)

        assertEquals(RunPhase.COOL_DOWN, driver.state.phase)
        assertEquals(RunLifecycle.RUNNING, driver.state.lifecycle)
        assertTrue(driver.state.intervalsFinished)
        assertTrue(effects.spoken().contains("Main workout complete, beginning cool down."))
    }

    @Test
    fun `the cool-down entered from the last interval says cool-down from that moment`() {
        val driver = Driver()
        driver.start(config(workout = COOLDOWN_WORKOUT))

        val effects = driver.advance(5)

        // The screen and the notification match what the coach just said, on the same second.
        assertEquals(
            "Cooldown • 00:30 left",
            effects.filterIsInstance<RunEffect.Notify>().last().text,
        )
    }

    @Test
    fun `a planned run cools down and ends itself without a skip`() {
        val driver = Driver()
        driver.start(config(workout = COOLDOWN_WORKOUT))

        driver.advance(5)
        val warning = driver.advance(20)
        val ending = driver.advance(10)

        assertEquals(listOf("10 seconds of cool down remaining"), warning.spoken())
        assertEquals(1, ending.count<RunEffect.FinalizeRun>())
        assertEquals(RunLifecycle.STOPPED, driver.state.lifecycle)
    }

    @Test
    fun `a workout with a nought-second cool-down ends rather than stranding the run`() {
        val driver = Driver()
        driver.start(config(workout = COOLDOWN_WORKOUT.copy(coolDownSeconds = 0)))

        driver.advance(5)
        assertEquals(RunPhase.COOL_DOWN, driver.state.phase)
        val effects = driver.advance(2)

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
    fun `the shade's pause and resume do what the button does`() {
        val driver = Driver()
        driver.start()

        driver.on(RunEvent.PauseRequested(driver.nowMillis))
        assertEquals(RunLifecycle.PAUSED, driver.state.lifecycle)

        driver.on(RunEvent.ResumeRequested(driver.nowMillis))
        assertEquals(RunLifecycle.RUNNING, driver.state.lifecycle)
    }

    @Test
    fun `a stale resume from the shade does not pause a running run`() {
        val driver = Driver()
        driver.start()

        val effects = driver.on(RunEvent.ResumeRequested(driver.nowMillis))

        assertTrue(effects.isEmpty())
        assertEquals(RunLifecycle.RUNNING, driver.state.lifecycle)
    }

    @Test
    fun `a stale pause from the shade does not resume a paused run`() {
        val driver = Driver()
        driver.start()
        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        val effects = driver.on(RunEvent.PauseRequested(driver.nowMillis))

        assertTrue(effects.isEmpty())
        assertEquals(RunLifecycle.PAUSED, driver.state.lifecycle)
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
    fun `a cool-down's final second is banked, so the bands sum to the duration`() {
        val driver = Driver()
        driver.start()
        driver.advance(60)
        driver.skipPhase()

        val finalize = driver.advance(30).only<RunEffect.FinalizeRun>()

        // 90 strapless seconds: the zone totals plus the no-data seconds equal the saved duration,
        // including the terminating second that used to be counted but never banked.
        val zones = finalize.totals.zoneSeconds
        val banked = zones.zone1 + zones.zone2 + zones.zone3 + zones.zone4 + zones.zone5 +
            finalize.totals.noDataSeconds
        assertEquals(finalize.totals.durationSeconds, banked)
        assertEquals(90L, finalize.totals.noDataSeconds)
    }

    @Test
    fun `a strapless thirty-second cool-down saves thirty no-data seconds, not twenty-nine`() {
        val driver = Driver()
        driver.start(config(workout = COOLDOWN_WORKOUT))

        val finalize = (driver.advance(5) + driver.advance(30)).only<RunEffect.FinalizeRun>()

        // Five strapless seconds to reach the cool-down, then a 30-second strapless cool-down whose
        // last second now counts: 35 no-data seconds, equal to the whole duration rather than one
        // short of it.
        assertEquals(35L, finalize.totals.durationSeconds)
        assertEquals(35L, finalize.totals.noDataSeconds)
    }

    @Test
    fun `the cool-down's final second writes an HR sample when a reading stands`() {
        val driver = Driver()
        driver.start()
        driver.advance(60)
        driver.skipPhase()

        val cooldown = driver.advanceWith(30, IN_TARGET)

        // One sample per second of the cool-down, the terminating second included.
        assertEquals(30, cooldown.count<RunEffect.SaveHrSample>())
    }

    @Test
    fun `a late tick over the cool-down line ends on the second it counted, not its own reading`() {
        val driver = Driver()
        driver.start()
        driver.skipPhase()
        driver.skipPhase()
        driver.advance(25)

        // The pulse that carries the Run over the 30-second line arrives 30 seconds late. It counts
        // the five seconds it had left and stamps the end time at the second it ended on — T0 plus
        // thirty seconds — rather than at its own reading, T0 plus fifty-five.
        val finalize = driver.advanceInOneTick(30).only<RunEffect.FinalizeRun>()

        assertEquals(30L, finalize.totals.durationSeconds)
        assertEquals(T0 + 30_000, finalize.totals.endedAtMillis)
        assertEquals(30L, finalize.totals.noDataSeconds)
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

        // The whole finalization: hand back GPS, let go of the strap, write the totals. Nothing
        // goes looking for the row to work out what to write.
        assertEquals(
            listOf(
                RunEffect.StopGps,
                RunEffect.ReleaseStrap,
                RunEffect.FinalizeRun(7L, driver.totalsOf(durationSeconds = 5)),
            ),
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
    fun `the main notification counts up for a run with no workout`() {
        val driver = Driver()
        driver.start(config(workout = null))
        driver.advance(1)

        val effects = driver.advanceInOneTick(10)

        assertEquals("Main elapsed 00:10", effects.only<RunEffect.Notify>().text)
    }

    @Test
    fun `the cool-down notification counts down`() {
        val driver = Driver()
        driver.start()
        driver.skipPhase()
        driver.skipPhase()

        val effects = driver.advanceInOneTick(5)

        assertEquals("Cooldown • 00:25 left", effects.only<RunEffect.Notify>().text)
    }

    @Test
    fun `the notification follows the run into its next phase`() {
        val driver = Driver()
        driver.start(config(workout = null))

        val effects = driver.advance(1)

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
    runType = state.config?.workout?.runType,
    averageBpm = state.tally.averageBpm,
    maxBpm = state.tally.maxBpm,
    zoneSeconds = state.tally.zoneSeconds,
    noDataSeconds = state.tally.noDataSeconds,
    walkBreaks = state.walkBreaks,
)
