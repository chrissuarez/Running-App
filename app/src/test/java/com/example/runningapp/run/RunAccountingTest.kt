package com.example.runningapp.run

import com.example.runningapp.HrProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Run counts, and what it saves.
 *
 * Every assertion goes through [Run.onEvent]: a reading is an event and a second is an event, so a
 * dropout is a list of them rather than a thread that has to be persuaded to misbehave. The
 * fixtures and the [Driver] live in `RunTestHarness.kt`.
 */
class RunAccountingTest {

    @Test
    fun `seconds are banked in the zone the reading falls in`() {
        val driver = Driver()
        driver.start()

        driver.advanceWith(seconds = 30, bpm = ABOVE_TARGET)

        assertEquals(30L, driver.state.tally.zoneSeconds.zone3)
        assertEquals(0L, driver.state.tally.noDataSeconds)
    }

    @Test
    fun `the zone is the one the max hr pinned at start says it is`() {
        val fitter = Driver()
        fitter.start(config = config(hrProfile = HrProfile(190)))
        fitter.advanceWith(seconds = 10, bpm = 150)

        val lower = Driver()
        lower.start(config = config(hrProfile = HrProfile(160)))
        lower.advanceWith(seconds = 10, bpm = 150)

        assertEquals(10L, fitter.state.tally.zoneSeconds.zone3)
        assertEquals(10L, lower.state.tally.zoneSeconds.zone5)
    }

    @Test
    fun `seconds above the target band are banked as well as in their zone`() {
        val driver = Driver()
        driver.start()

        driver.advanceWith(seconds = 10, bpm = IN_TARGET)
        driver.advanceWith(seconds = 15, bpm = ABOVE_TARGET)

        assertEquals(15L, driver.state.tally.aboveTargetSeconds)
    }

    @Test
    fun `a run with no strap banks every second as no data`() {
        val driver = Driver()
        driver.start()

        driver.advance(45)

        assertEquals(45L, driver.state.tally.noDataSeconds)
        assertEquals(0, driver.state.tally.maxBpm)
        assertEquals(0, driver.state.tally.averageBpm)
    }

    @Test
    fun `a dropout mid-run banks the missing seconds rather than dropping them`() {
        val driver = Driver()
        driver.start()

        driver.advanceWith(seconds = 20, bpm = IN_TARGET)
        driver.heartRateLost()
        driver.advance(40)

        assertEquals(20L, driver.state.tally.zoneSeconds.zone2)
        assertEquals(40L, driver.state.tally.noDataSeconds)
        // The whole minute is accounted for: nothing vanished, and nothing was invented from the
        // last reading either.
        assertEquals(60L, driver.state.secondsRunning)
    }

    @Test
    fun `a dropout writes no sample for the seconds it covers`() {
        val driver = Driver()
        driver.start()
        driver.advanceWith(seconds = 5, bpm = IN_TARGET)

        driver.heartRateLost()
        val effects = driver.advance(10)

        assertEquals(0, effects.count<RunEffect.SaveHrSample>())
    }

    @Test
    fun `the run's maximum and average heart rate are the seconds that had one`() {
        val driver = Driver()
        driver.start()

        driver.advanceWith(listOf(120, 160, 130, 130))
        driver.heartRateLost()
        driver.advance(20)

        assertEquals(160, driver.state.tally.maxBpm)
        assertEquals(135, driver.state.tally.averageBpm)
    }

    @Test
    fun `each second with a reading is saved as a sample`() {
        val driver = Driver()
        driver.start()

        val effects = driver.advanceWith(seconds = 3, bpm = IN_TARGET)

        val samples = effects.filterIsInstance<RunEffect.SaveHrSample>().map { it.sample }
        assertEquals(listOf(1L, 2L, 3L), samples.map { it.elapsedSeconds })
        assertEquals(listOf(IN_TARGET, IN_TARGET, IN_TARGET), samples.map { it.rawBpm })
        assertTrue(effects.filterIsInstance<RunEffect.SaveHrSample>().all { it.runRowId == 7L })
    }

    @Test
    fun `a sample carries the smoothed reading, not just the raw one`() {
        val driver = Driver()
        driver.start()

        val effects = driver.advanceWith(listOf(100, 120))

        val samples = effects.filterIsInstance<RunEffect.SaveHrSample>().map { it.sample }
        assertEquals(listOf(100, 120), samples.map { it.rawBpm })
        assertEquals(listOf(100, 110), samples.map { it.smoothedBpm })
    }

    @Test
    fun `the smoothed reading averages the strap while coaching is off`() {
        val driver = Driver()
        driver.start(controls = RunControls(coachingEnabled = false))

        val effects = driver.advanceWith(listOf(100, 120))

        val samples = effects.filterIsInstance<RunEffect.SaveHrSample>().map { it.sample }
        assertEquals(listOf(100, 110), samples.map { it.smoothedBpm })
    }

    @Test
    fun `the smoothed reading averages the strap through the cool-down`() {
        val driver = Driver()
        driver.start()
        driver.skipPhase()
        driver.skipPhase()

        val effects = driver.advanceWith(listOf(100, 120))

        assertEquals(RunPhase.COOL_DOWN, driver.state.phase)
        val samples = effects.filterIsInstance<RunEffect.SaveHrSample>().map { it.sample }
        assertEquals(listOf(100, 110), samples.map { it.smoothedBpm })
    }

    @Test
    fun `coaching switched back on finds a full window, not a single reading`() {
        val driver = Driver()
        driver.start(controls = RunControls(coachingEnabled = false))
        driver.advanceWith(seconds = 30, bpm = 100)

        driver.controls(RunControls(coachingEnabled = true))
        driver.advanceWith(seconds = 1, bpm = 160)

        // Five seconds of 100 and one beat of 160: the coach's first decision is smoothed, where a
        // window that had not been filling would have handed it the bare 160.
        assertEquals(110, driver.state.heartRate.smoothedBpm)
    }

    @Test
    fun `the strap going away empties the window, so nothing averages across a dropout`() {
        val driver = Driver()
        driver.start()
        driver.advanceWith(seconds = 3, bpm = 100)

        driver.heartRateLost()
        driver.advanceWith(seconds = 1, bpm = 160)

        assertEquals(160, driver.state.heartRate.smoothedBpm)
    }

    @Test
    fun `readings age out of the window while the coach is not listening`() {
        val driver = Driver()
        driver.start(controls = RunControls(coachingEnabled = false))

        driver.advanceWith(seconds = 3, bpm = 100)
        driver.advance(10)
        driver.heartRate(160)

        assertEquals(160, driver.state.heartRate.smoothedBpm)
    }

    @Test
    fun `a sample records the strap's state at the second it was taken`() {
        val driver = Driver()
        driver.start()

        val effects = driver.advanceWith(seconds = 1, bpm = IN_TARGET, connectionStatus = "Connected")

        assertEquals(CONNECTED, effects.only<RunEffect.SaveHrSample>().sample.connectionStatus)
    }

    @Test
    fun `the seconds before the row id arrives are held and flushed in order`() {
        val driver = Driver()
        driver.start(withRow = false)

        val duringWait = driver.advanceWith(seconds = 3, bpm = IN_TARGET)
        assertEquals(0, duringWait.count<RunEffect.SaveHrSample>())

        val flushed = driver.rowCreated(runRowId = 12L)

        val samples = flushed.filterIsInstance<RunEffect.SaveHrSample>()
        assertEquals(listOf(1L, 2L, 3L), samples.map { it.sample.elapsedSeconds })
        assertTrue(samples.all { it.runRowId == 12L })
    }

    @Test
    fun `a pulse that catches up banks every second it accounts for`() {
        val driver = Driver()
        driver.start()
        driver.heartRate(ABOVE_TARGET)

        driver.advanceInOneTick(5)

        // One reading, five seconds: the Run banks the reading it has rather than skipping the
        // seconds the phone was asleep for.
        assertEquals(5L, driver.state.tally.zoneSeconds.zone3)
        assertEquals(5L, driver.state.secondsRunning)
    }

    @Test
    fun `a paused run banks nothing`() {
        val driver = Driver()
        driver.start()
        driver.advanceWith(seconds = 10, bpm = IN_TARGET)

        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.advanceWith(seconds = 30, bpm = IN_TARGET)

        assertEquals(10L, driver.state.tally.zoneSeconds.zone2)
        assertEquals(0L, driver.state.tally.noDataSeconds)
        assertEquals(30L, driver.state.secondsPaused)
    }

    @Test
    fun `the finished run is saved with what it counted`() {
        val driver = Driver()
        driver.start()
        driver.advanceWith(seconds = 10, bpm = 130)
        driver.heartRateLost()
        driver.advance(5)

        val finalize = driver.stop().only<RunEffect.FinalizeRun>()

        assertEquals(130, finalize.totals.averageBpm)
        assertEquals(130, finalize.totals.maxBpm)
        assertEquals(10L, finalize.totals.zoneSeconds.zone2)
        assertEquals(5L, finalize.totals.noDataSeconds)
        assertEquals(15L, finalize.totals.durationSeconds)
    }

    @Test
    fun `the finished run is saved with what it cost`() {
        val driver = Driver()
        driver.start()
        // Twenty minutes at 150 bpm — Zone 3 of a Max HR of 190, so weight 3.
        driver.advanceWith(seconds = 20 * 60, bpm = ABOVE_TARGET)

        val finalize = driver.stop().only<RunEffect.FinalizeRun>()

        assertEquals(60, finalize.totals.effortScore)
    }

    @Test
    fun `a walk break is scored as the walking it was, not averaged into the running`() {
        val steady = Driver()
        steady.start()
        steady.advanceWith(seconds = 20 * 60, bpm = 132)

        val runWalk = Driver()
        runWalk.start()
        // The same twenty minutes and the same average of 132, half run hard and half walked off.
        repeat(4) {
            runWalk.advanceWith(seconds = 150, bpm = 174)
            runWalk.advanceWith(seconds = 150, bpm = 90)
        }

        val steadyScore = steady.stop().only<RunEffect.FinalizeRun>().totals.effortScore!!
        val runWalkScore = runWalk.stop().only<RunEffect.FinalizeRun>().totals.effortScore!!

        assertTrue("run/walk $runWalkScore should beat steady $steadyScore", runWalkScore > steadyScore)
    }

    @Test
    fun `an unplanned walk on a treadmill is scored like any other run`() {
        // #61 scores every kind of session that has a heart rate — a Zone 2 walk and a treadmill
        // Run included. There is one recorder, so this is true by construction; pinned because the
        // ticket asks for it by name.
        val driver = Driver()
        driver.start(config = config(workout = null, runMode = RunMode.TREADMILL))
        driver.advanceWith(seconds = 30 * 60, bpm = 120)

        assertEquals(60, driver.stop().only<RunEffect.FinalizeRun>().totals.effortScore)
    }

    @Test
    fun `a run that read no heart rate has no score rather than a zero`() {
        val driver = Driver()
        driver.start()
        driver.advance(45)

        assertNull(driver.stop().only<RunEffect.FinalizeRun>().totals.effortScore)
    }

    @Test
    fun `seconds below zone 1 cost nothing`() {
        val driver = Driver()
        driver.start()
        // 90 bpm is under Zone 1's lower edge of 95: a heart rate, but not training.
        driver.advanceWith(seconds = 20 * 60, bpm = 90)

        assertEquals(0, driver.stop().only<RunEffect.FinalizeRun>().totals.effortScore)
    }

    @Test
    fun `a new run counts nothing from the one before it`() {
        val driver = Driver()
        driver.start()
        driver.advanceWith(seconds = 30, bpm = ABOVE_TARGET)
        driver.stop()

        driver.nowMillis += 5_000
        driver.start(runRowId = 8L)

        assertEquals(RunTally(), driver.state.tally)
        assertEquals(0, driver.state.walkBreaks)
        assertEquals(0, driver.state.heartRate.bpm)
        assertEquals(RunCoaching(), driver.state.coaching)
    }
}
