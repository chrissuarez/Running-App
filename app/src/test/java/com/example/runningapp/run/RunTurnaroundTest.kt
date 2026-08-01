package com.example.runningapp.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The halfway turnaround (#208).
 *
 * [PLANNED_WORKOUT] is 60s of warm-up, 6 × (180 + 60) of main and 30s of cool-down: 1530 seconds
 * door to door, so halfway is second 765. Halving the main Phase alone would put it at 780, which
 * is what makes 765 worth asserting rather than "somewhere in the middle".
 */
class RunTurnaroundTest {

    private val outdoor = config(runMode = RunMode.OUTDOOR)

    @Test
    fun `an outdoor planned run calls the turnaround at half its whole length`() {
        val driver = Driver()
        driver.start(outdoor)

        assertEquals(emptyList<String>(), driver.advance(764).held())
        assertEquals(listOf(TURNAROUND_CUE), driver.advance(1).held())
    }

    @Test
    fun `the turnaround is one that may wait for a gap, not one that cuts in`() {
        val driver = Driver()
        driver.start(outdoor)

        val effects = driver.advance(765)
        assertEquals(listOf(TURNAROUND_CUE), effects.held())
        assertTrue("the turnaround must not be spoken immediately", TURNAROUND_CUE !in effects.spoken())
    }

    @Test
    fun `the turnaround is called once and never again`() {
        val driver = Driver()
        driver.start(outdoor)

        assertEquals(1, driver.advance(1_200).held().size)
    }

    @Test
    fun `a pause moves the turnaround later by the time spent standing still`() {
        val driver = Driver()
        driver.start(outdoor)
        driver.advance(700)

        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        assertEquals(emptyList<String>(), driver.advance(100).held())
        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        // 700 moving seconds are behind the Run, so 65 more reach halfway — not the 65 that the
        // wall clock, now 165 seconds further on, would have reached long ago.
        assertEquals(emptyList<String>(), driver.advance(64).held())
        assertEquals(listOf(TURNAROUND_CUE), driver.advance(1).held())
    }

    @Test
    fun `skipping the warm up shortens the run and brings the turnaround forward`() {
        val driver = Driver()
        driver.start(outdoor)
        driver.advance(10)
        driver.skipPhase()

        // 10 + 1440 + 30 = 1480 seconds left to run, so halfway is second 740, not 765.
        assertEquals(emptyList<String>(), driver.advance(729).held())
        assertEquals(listOf(TURNAROUND_CUE), driver.advance(1).held())
    }

    @Test
    fun `a run skipped to its cool down never calls the turnaround`() {
        val driver = Driver()
        driver.start(outdoor)
        driver.advance(460)
        driver.skipPhase()

        // Halfway is behind the runner now, and they are heading home: "turn around" would be
        // actively wrong, so nothing is said at all.
        assertEquals(emptyList<String>(), driver.advance(29).held())
    }

    @Test
    fun `a treadmill run has nowhere to turn around to`() {
        val driver = Driver()
        driver.start(config(runMode = RunMode.TREADMILL))

        assertEquals(emptyList<String>(), driver.advance(1_200).held())
    }

    @Test
    fun `a run following no workout has no length to halve`() {
        val driver = Driver()
        driver.start(config(workout = null, runMode = RunMode.OUTDOOR))

        assertEquals(emptyList<String>(), driver.advance(1_200).held())
    }

    @Test
    fun `the runner doing loops turns the turnaround off`() {
        val driver = Driver()
        driver.start(outdoor, RunControls(turnaroundCueEnabled = false))

        assertEquals(emptyList<String>(), driver.advance(1_200).held())
    }

    @Test
    fun `switching the turnaround on after halfway does not call one that has gone`() {
        val driver = Driver()
        driver.start(outdoor, RunControls(turnaroundCueEnabled = false))
        driver.advance(1_000)

        driver.controls(RunControls(turnaroundCueEnabled = true))

        // Halfway is a fact about the Run, not about the setting. Telling a runner 250 seconds from
        // the end to turn around would send them out again.
        assertEquals(emptyList<String>(), driver.advance(200).held())
    }

    @Test
    fun `switching it on while a pulse is overdue does not uncover a halfway already run`() {
        val driver = Driver()
        driver.start(outdoor, RunControls(turnaroundCueEnabled = false))
        driver.advance(700)

        // The phone dozes: no pulse for a hundred seconds, so the Run's clock still reads 700 and
        // second 765 has been run but not yet accounted. The switch arrives into that gap.
        driver.nowMillis += 100_000
        val flipped = driver.controls(RunControls(turnaroundCueEnabled = true))

        // Those hundred seconds were run with the cue off. Settling them under the new setting is
        // what used to let the catch-up speak a halfway the runner is already well past.
        assertEquals(emptyList<String>(), flipped.held())
        assertEquals(emptyList<String>(), driver.advance(200).held())
    }

    @Test
    fun `turning the cue off while it waits takes it back`() {
        val driver = Driver()
        driver.start(outdoor)
        assertEquals(listOf(TURNAROUND_CUE), driver.advance(765).held())

        // It may be up to fifteen seconds from being spoken. The runner has just said they do not
        // want it.
        assertTrue(RunEffect.DropWaitingCue in driver.controls(RunControls(turnaroundCueEnabled = false)))
    }

    @Test
    fun `skipping into the cool down takes back a turnaround still waiting`() {
        val driver = Driver()
        driver.start(outdoor)
        driver.advance(765)

        assertTrue(RunEffect.DropWaitingCue in driver.skipPhase())
    }

    @Test
    fun `halfway landing on an interval is said after the interval's own instruction`() {
        // 10s warm-up, 3 × (10 run / 10 walk), 8s cool-down: 78 seconds door to door, so halfway is
        // second 39 — the exact second the second Interval's walk begins.
        val short = PLANNED_WORKOUT.copy(
            runDurationSeconds = 10,
            walkDurationSeconds = 10,
            totalRepeats = 3,
            warmUpSeconds = 10,
            coolDownSeconds = 8,
        )
        val driver = Driver()
        driver.start(config(workout = short, runMode = RunMode.OUTDOOR))
        driver.advance(38)

        val halfway = driver.advance(1)
        assertEquals(listOf(TURNAROUND_CUE), halfway.held())
        // The instruction is registered with the speaker first. Handed over the other way round,
        // the held cue could be released into the gap between the two and then flushed by the
        // instruction itself.
        val instruction = halfway.indexOfFirst { it is RunEffect.Speak }
        val turnaround = halfway.indexOfFirst { it is RunEffect.SpeakWhenQuiet }
        assertTrue("expected the interval instruction before the turnaround, got $halfway", instruction in 0 until turnaround)
    }

    @Test
    fun `halfway landing on the second the cool down begins says nothing`() {
        // 20s warm-up, 2 × (10 run / 10 walk), 60s cool-down: 120 seconds door to door, so halfway
        // is second 60 — the exact second the last Interval completes and the cool-down begins.
        val backLoaded = PLANNED_WORKOUT.copy(
            runDurationSeconds = 10,
            walkDurationSeconds = 10,
            totalRepeats = 2,
            warmUpSeconds = 20,
            coolDownSeconds = 60,
        )
        val driver = Driver()
        driver.start(config(workout = backLoaded, runMode = RunMode.OUTDOOR))

        val handover = driver.advance(60)
        assertTrue(
            "the cool-down begins on this second",
            "Main workout complete, beginning cool down." in handover.spoken(),
        )
        // Halfway is decided before the Intervals move and said after them, so this is the one
        // second where the two disagree. The runner is heading home either way.
        assertEquals(emptyList<String>(), handover.held())
        assertEquals(emptyList<String>(), driver.advance(60).held())
    }

    @Test
    fun `nothing is taken back when nothing is waiting`() {
        val driver = Driver()
        driver.start(outdoor)
        driver.advance(460)

        assertTrue(RunEffect.DropWaitingCue !in driver.controls(RunControls(turnaroundCueEnabled = false)))
        assertTrue(RunEffect.DropWaitingCue !in driver.skipPhase())
    }
}
