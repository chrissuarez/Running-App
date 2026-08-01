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
}
